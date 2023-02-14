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
package tech.beshu.ror.es.ssl

import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.ssl.NotSslRecordException
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.util.BigArrays
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.http.netty4.Netty4HttpServerTransport
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.configuration.SslConfiguration.ExternalSslConfiguration
import tech.beshu.ror.utils.SSLCertHelper

class SSLNetty4HttpServerTransport(settings: Settings,
                                   networkService: NetworkService,
                                   bigArrays: BigArrays,
                                   threadPool: ThreadPool,
                                   xContentRegistry: NamedXContentRegistry,
                                   dispatcher: HttpServerTransport.Dispatcher,
                                   ssl: ExternalSslConfiguration,
                                   fipsCompliant: Boolean)
  extends Netty4HttpServerTransport(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher)
    with Logging {

  private val serverSslContext = SSLCertHelper.prepareServerSSLContext(ssl, fipsCompliant, ssl.clientAuthenticationEnabled)

  override def configureServerChannelHandler = new SSLHandler(this)

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    if (!this.lifecycle.started) return
    else if (cause.getCause.isInstanceOf[NotSslRecordException]) logger.warn(s"${cause.getMessage} connecting from: ${ctx.channel().remoteAddress()}")
    else super.exceptionCaught(ctx, cause)
    ctx.channel().flush().close()
  }

  final class SSLHandler(transport: Netty4HttpServerTransport)
    extends Netty4HttpServerTransport.HttpChannelHandler(
      transport, SSLNetty4HttpServerTransport.this.detailedErrorsEnabled, threadPool.getThreadContext
    ) {

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      ch.pipeline().addFirst("ssl_netty4_handler", serverSslContext.newHandler(ch.alloc()))
    }
  }
}
