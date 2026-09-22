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

import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext}

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
 * ROR ships a newer netty than ES does. Remove this handler when both use the same netty.
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
