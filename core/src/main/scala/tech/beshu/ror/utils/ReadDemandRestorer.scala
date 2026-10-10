/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.utils

import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPipeline}
import io.netty.handler.flow.FlowControlHandler

/**
 * Keeps the read demand of the ES HTTP pipeline alive.
 *
 * ES 9.1+ turns auto-read off and puts a netty `FlowControlHandler` in the HTTP pipeline, so the transport
 * reads only what it asks for. netty 4.1.136+ clears the `FlowControlHandler` read demand on every
 * `channelReadComplete`. A read that decodes no message - a partial TLS record, a swallowed empty chunk, a
 * TLS handshake round trip - therefore drops the demand. The next message stays in the `FlowControlHandler`
 * queue, no side asks for a new read, and the connection stalls with the request body in the socket buffer.
 *
 * This handler counts the reads that pass through it and the messages that come back. When a
 * `channelReadComplete` arrives while a read is still unsatisfied, it asks for one more read and restores
 * the demand that the `FlowControlHandler` dropped.
 *
 * It must sit directly after the `FlowControlHandler`, so that its `ctx.read()` goes through the
 * `FlowControlHandler` and increments the counter again. All callbacks run on the channel event loop, so
 * the counter needs no synchronization.
 *
 * ROR ships a newer netty than ES does, which is how the ES pipeline gets this behaviour. Remove the
 * handler when netty stops clearing the read demand on `channelReadComplete`, or when ES stops turning
 * auto-read off. Equal netty versions alone do not end the hazard (RORDEV-2239).
 */
final class ReadDemandRestorer extends ChannelDuplexHandler {

  // The ES channel initializer calls ch.read() before this handler is in the pipeline. That read is
  // unsatisfied at install time, so the count starts at one.
  private var unsatisfiedReads = 1

  override def read(ctx: ChannelHandlerContext): Unit = {
    unsatisfiedReads += 1
    ctx.read()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    if (unsatisfiedReads > 0) unsatisfiedReads -= 1
    ctx.fireChannelRead(msg)
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.fireChannelReadComplete()
    if (unsatisfiedReads > 0) {
      // ctx.read() starts at the next outbound handler, so it does not re-enter read() above
      ctx.read()
    }
  }

}

object ReadDemandRestorer extends RequestIdAwareLogging {

  private val handlerName = "ror_read_demand_restorer"

  /**
   * Puts the handler directly after the `FlowControlHandler` of the given pipeline.
   *
   * ES 9.1+ always adds that handler, so a pipeline without one means ES changed its HTTP pipeline. The
   * method then logs and adds nothing, because the position decides whether the handler works at all.
   */
  def installIn(pipeline: ChannelPipeline): Unit = {
    Option(pipeline.context(classOf[FlowControlHandler])) match {
      case Some(flowControlContext) =>
        pipeline.addAfter(flowControlContext.name(), handlerName, new ReadDemandRestorer())
      case None =>
        noRequestIdLogger.warn(
          s"No FlowControlHandler in the HTTP pipeline, so ROR does not add the $handlerName handler. " +
            "Check that large requests over ROR SSL still complete."
        )
    }
  }

}
