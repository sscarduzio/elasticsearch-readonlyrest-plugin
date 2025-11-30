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

import io.netty.channel.Channel
import io.netty.handler.ssl.NotSslRecordException
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings.{ClusterSettings, Settings}
import org.elasticsearch.http.netty4.Netty4HttpServerTransport
import org.elasticsearch.http.{HttpChannel, HttpServerTransport}
import org.elasticsearch.telemetry.tracing.Tracer
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.netty4.{SharedGroupFactory, TLSConfig}
import org.elasticsearch.xcontent.NamedXContentRegistry
import tech.beshu.ror.configuration.SslConfiguration.ExternalSslConfiguration
import tech.beshu.ror.utils.SSLCertHelper

class SSLNetty4HttpServerTransport(settings: Settings,
                                   networkService: NetworkService,
                                   threadPool: ThreadPool,
                                   xContentRegistry: NamedXContentRegistry,
                                   dispatcher: HttpServerTransport.Dispatcher,
                                   ssl: ExternalSslConfiguration,
                                   clusterSettings: ClusterSettings,
                                   sharedGroupFactory: SharedGroupFactory,
                                   tracer: Tracer,
                                   fipsCompliant: Boolean)
  extends Netty4HttpServerTransport(settings, networkService, threadPool, xContentRegistry, dispatcher, clusterSettings, sharedGroupFactory, tracer, TLSConfig.noTLS(), null, null)
    with RequestIdAwareLogging {

  private val serverSslContext = SSLCertHelper.prepareServerSSLContext(ssl, fipsCompliant, ssl.clientAuthenticationEnabled)

  override def configureServerChannelHandler = new SSLHandler(this)

  override def onException(channel: HttpChannel, cause: Exception): Unit = {
    if (!this.lifecycle.started) return
    else if (cause.getCause.isInstanceOf[NotSslRecordException]) noRequestIdLogger.warn(cause.getMessage + " connecting from: " + channel.getRemoteAddress)
    else super.onException(channel, cause)
    channel.close()
  }

  final class SSLHandler(transport: Netty4HttpServerTransport)
    extends Netty4HttpServerTransport.HttpChannelHandler(transport, handlingSettings, TLSConfig.noTLS(), null, null) {

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      ch.pipeline().addFirst("ssl_netty4_handler", serverSslContext.newHandler(ch.alloc()))
    }
  }
}
