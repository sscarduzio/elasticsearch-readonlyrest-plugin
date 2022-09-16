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

import io.netty.channel._
import io.netty.handler.ssl._
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.Version
import org.elasticsearch.cluster.node.DiscoveryNode
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.util.PageCacheRecycler
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.netty4.{Netty4Transport, SharedGroupFactory}
import tech.beshu.ror.configuration.SslConfiguration.InternodeSslConfiguration
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.SSLCertHelper

import java.net.SocketAddress
import javax.net.ssl.{SNIHostName, SNIServerName}

class SSLNetty4InternodeServerTransport(settings: Settings,
                                        threadPool: ThreadPool,
                                        pageCacheRecycler: PageCacheRecycler,
                                        circuitBreakerService: CircuitBreakerService,
                                        namedWriteableRegistry: NamedWriteableRegistry,
                                        networkService: NetworkService,
                                        ssl: InternodeSslConfiguration,
                                        sharedGroupFactory: SharedGroupFactory,
                                        fipsCompliant: Boolean)
  extends Netty4Transport(settings, Version.CURRENT, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService, sharedGroupFactory)
    with Logging {

  override def getClientChannelInitializer(node: DiscoveryNode): ChannelHandler = new ClientChannelInitializer {
    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      logger.info(">> internode SSL channel initializing")

      //      val keyManagerFactory = SSLCertHelper.keyManagerFactory(ssl, fipsCompliant)
      //      val sslContext = SSLContext.getInstance("TLSv1.3")
      //      sslContext.init(keyManagerFactory.getKeyManagers, usedTrustManager.getTrustManagers, null)

      val sslCtx = SSLCertHelper.prepareClientSSLContext(ssl, fipsCompliant, ssl.certificateVerificationEnabled)
      ch.pipeline().addFirst(new ChannelOutboundHandlerAdapter {
        override def connect(ctx: ChannelHandlerContext, remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): Unit = {
          val sslEngine = sslCtx.newEngine(ctx.alloc())
          sslEngine.setUseClientMode(true)
          sslEngine.setNeedClientAuth(true)

          import scala.collection.JavaConverters._
          val configuredServerName = node.getAttributes().get("server_name")
          val sslParameters = sslEngine.getSSLParameters
          val serverNames: List[SNIServerName] = List(new SNIHostName("c5ea024e4a414aa4a6327dc0dda5350b.us-central1.gcp.cloud.es.io"))
          sslParameters.setServerNames(serverNames.asJava)
          sslEngine.setSSLParameters(sslParameters)

          ctx.pipeline().replace(this, "internode_ssl_client", new SslHandler(sslEngine))
          super.connect(ctx, remoteAddress, localAddress, promise)
        }
      })
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      if (cause.isInstanceOf[NotSslRecordException] || (cause.getCause != null && cause.getCause.isInstanceOf[NotSslRecordException])) {
        logger.error("Receiving non-SSL connections from: (" + ctx.channel.remoteAddress + "). Will disconnect")
        ctx.channel.close
      } else {
        super.exceptionCaught(ctx, cause)
      }
    }
  }

  override def getServerChannelInitializer(name: String): ChannelHandler = new SslChannelInitializer(name)

  private class SslChannelInitializer(name: String) extends ServerChannelInitializer(name) {
    private var context = Option.empty[SslContext]

    doPrivileged {
      context = Option(SSLCertHelper.prepareServerSSLContext(ssl, fipsCompliant, clientAuthenticationEnabled = false))
    }

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      context.foreach { sslCtx =>
        ch.pipeline().addFirst("ror_internode_ssl_handler", sslCtx.newHandler(ch.alloc()))
      }
    }
  }
}
