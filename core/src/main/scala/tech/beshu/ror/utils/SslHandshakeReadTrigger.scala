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

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.ssl.SslHandshakeCompletionEvent

/**
 * After a successful TLS handshake, issues one extra read on the first channelReadComplete so that a
 * FlowControlHandler sitting downstream forwards the first HTTP request instead of leaving it queued.
 *
 * Must be installed before the FlowControlHandler (inbound order) so it observes channelReadComplete
 * even when the FlowControlHandler would drop it at unsatisfiedReads==0. See the usage site in each
 * es{version}x SSLNetty4HttpServerTransport for why this is needed (ES 9.1+ with netty >= 4.1.136).
 */
final class SslHandshakeReadTrigger extends ChannelInboundHandlerAdapter {
  private var waitForReadComplete = false

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: AnyRef): Unit = {
    evt match {
      case e: SslHandshakeCompletionEvent if e.isSuccess => waitForReadComplete = true
      case _                                             =>
    }
    ctx.fireUserEventTriggered(evt)
  }

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.fireChannelReadComplete()
    if (waitForReadComplete) {
      waitForReadComplete = false
      ctx.channel().read()
    }
  }

}
