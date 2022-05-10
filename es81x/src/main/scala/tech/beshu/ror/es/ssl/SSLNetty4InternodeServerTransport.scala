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

import java.io.InputStream
import java.net.SocketAddress

import io.netty.buffer.ByteBufAllocator
import io.netty.channel._
import io.netty.handler.ssl._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
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
import tech.beshu.ror.utils.SslCertParser

import scala.collection.JavaConverters._

class SSLNetty4InternodeServerTransport(settings: Settings,
                                        threadPool: ThreadPool,
                                        pageCacheRecycler: PageCacheRecycler,
                                        circuitBreakerService: CircuitBreakerService,
                                        namedWriteableRegistry: NamedWriteableRegistry,
                                        networkService: NetworkService,
                                        ssl: InternodeSslConfiguration,
                                        sharedGroupFactory: SharedGroupFactory)
  extends Netty4Transport(settings, Version.CURRENT, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService, sharedGroupFactory)
    with Logging {

  override def getClientChannelInitializer(node: DiscoveryNode): ChannelHandler = new ClientChannelInitializer {
    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      logger.info(">> internode SSL channel initializing")
      val usedTrustManager =
        if (ssl.certificateVerificationEnabled) SslCertParser.customTrustManagerFrom(ssl).orNull
        else InsecureTrustManagerFactory.INSTANCE

      val sslCtx = SslContextBuilder.forClient()
        .trustManager(usedTrustManager)
        .build()
      ch.pipeline().addFirst(new ChannelOutboundHandlerAdapter {
        override def connect(ctx: ChannelHandlerContext, remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): Unit = {
          val sslEngine = sslCtx.newEngine(ctx.alloc())
          sslEngine.setUseClientMode(true)
          sslEngine.setNeedClientAuth(true)
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
      SslCertParser.run(new SSLContextCreatorImpl, ssl)
    }

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      context.foreach { sslCtx =>
        ch.pipeline().addFirst("ror_internode_ssl_handler", sslCtx.newHandler(ch.alloc()))
      }
    }

    private class SSLContextCreatorImpl extends SslCertParser.SSLContextCreator {
      override def mkSSLContext(certChain: InputStream, privateKey: InputStream): Unit = {
        try { // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          val sslCtxBuilder = SslContextBuilder.forServer(certChain, privateKey, null)

          logger.info("ROR Internode using SSL provider: " + SslContext.defaultServerProvider.name)
          SslCertParser.validateProtocolAndCiphers(sslCtxBuilder.build.newEngine(ByteBufAllocator.DEFAULT), ssl)
          if (ssl.allowedCiphers.nonEmpty) sslCtxBuilder.ciphers(ssl.allowedCiphers.map(_.value).toList.asJava)
          if (ssl.allowedProtocols.nonEmpty) sslCtxBuilder.protocols(ssl.allowedProtocols.map(_.value).toList: _*)
          context = Some(sslCtxBuilder.build)
        } catch { case e: Throwable =>
            context = None
            logger.error("Failed to load SSL CertChain & private key from Keystore! " + e.getClass.getSimpleName + ": " + e.getMessage, e)
        }
      }
    }
  }

}
