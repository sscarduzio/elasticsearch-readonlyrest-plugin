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
package org.elasticsearch.transport.netty4

import io.netty.channel.{Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.ssl.{NotSslRecordException, SslHandshakeCompletionEvent}
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings.{ClusterSettings, Settings}
import org.elasticsearch.http.netty4.Netty4HttpServerTransport
import org.elasticsearch.http.{HttpChannel, HttpServerTransport}
import org.elasticsearch.telemetry.tracing.Tracer
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.xcontent.NamedXContentRegistry
import tech.beshu.ror.settings.es.SslSettings.ExternalSslSettings
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.SSLCertHelper

class SSLNetty4HttpServerTransport(
    settings: Settings,
    networkService: NetworkService,
    threadPool: ThreadPool,
    xContentRegistry: NamedXContentRegistry,
    dispatcher: HttpServerTransport.Dispatcher,
    ssl: ExternalSslSettings,
    clusterSettings: ClusterSettings,
    sharedGroupFactory: SharedGroupFactory,
    tracer: Tracer
) extends Netty4HttpServerTransport(
      settings,
      networkService,
      threadPool,
      xContentRegistry,
      dispatcher,
      clusterSettings,
      sharedGroupFactory,
      tracer,
      TLSConfig.noTLS(),
      null,
      null
    )
    with RequestIdAwareLogging {

  private val serverSslContext = doPrivileged {
    SSLCertHelper.prepareServerSSLContext(ssl, ssl.clientAuthenticationEnabled)
  }

  override def configureServerChannelHandler = new SSLHandler(this)

  override def onException(channel: HttpChannel, cause: Exception): Unit = {
    if (!this.lifecycle.started) return
    else if (cause.getCause.isInstanceOf[NotSslRecordException])
      noRequestIdLogger.warn(cause.getMessage + " connecting from: " + channel.getRemoteAddress)
    else super.onException(channel, cause)
    channel.close()
  }

  final class SSLHandler(transport: Netty4HttpServerTransport)
      extends Netty4HttpServerTransport.HttpChannelHandler(transport, handlingSettings, TLSConfig.noTLS(), null, null) {

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      ch.pipeline().addFirst("ssl_netty4_handler", serverSslContext.newHandler(ch.alloc()))
      // TLS handshake hang on ES 9.1+ with netty >= 4.1.136.
      // ES 9.1 (elastic/elasticsearch#127817) disabled auto-read and added a
      // FlowControlHandler that reads only on demand (`unsatisfiedReads` counter).
      // ES < 9.1 (incl. es90x) has neither, so is NOT affected — don't add this there.
      // netty 4.1.136 (netty#15053) made channelReadComplete reset unsatisfiedReads=0;
      // each TLS handshake round-trip fires it, so the initial ch.read() is undone and
      // the first HTTP request stays queued in FlowControlHandler forever (client hangs).
      // Fix: after the handshake's channelReadComplete, issue one read to bump the
      // counter back to 1. Must sit before FlowControlHandler (inbound) to see the event
      // at count 0; use channel().read() (not ctx.read()) so the read reaches it from the tail.
      // Workaround tied to netty#15053 behavior — recheck (and possibly drop) on the next netty bump.
      ch.pipeline().addAfter("ssl_netty4_handler", "ssl_flow_control_read_trigger", new SslHandshakeReadTrigger())
    }

  }

  private final class SslHandshakeReadTrigger extends ChannelInboundHandlerAdapter {
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

}
