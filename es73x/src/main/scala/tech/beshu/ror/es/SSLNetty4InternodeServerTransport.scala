package tech.beshu.ror.es

import java.io.ByteArrayInputStream
import java.net.SocketAddress
import java.nio.charset.StandardCharsets

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
import org.elasticsearch.transport.netty4.Netty4Transport
import tech.beshu.ror.configuration.SslConfiguration
import tech.beshu.ror.es.utils.AccessControllerHelper.doPrivileged

import scala.collection.JavaConverters._

class SSLNetty4InternodeServerTransport(settings: Settings,
                                        threadPool: ThreadPool,
                                        pageCacheRecycler: PageCacheRecycler,
                                        circuitBreakerService: CircuitBreakerService,
                                        namedWriteableRegistry: NamedWriteableRegistry,
                                        networkService: NetworkService,
                                        ssl: SslConfiguration)
  extends Netty4Transport(settings, Version.CURRENT, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService)
    with Logging {

  override def getClientChannelInitializer(node: DiscoveryNode): ChannelHandler = new ClientChannelInitializer {
    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      logger.info(">> internode SSL channel initializing")
      val sslCtxBuilder = SslContextBuilder.forClient()
      if (ssl.verifyClientAuth) {
        sslCtxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE)
      }
      val sslCtx = sslCtxBuilder.build()
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

    import tech.beshu.ror.utils.SSLCertParser

    doPrivileged {
      SSLCertParser.run(new SSLContextCreatorImpl, ssl)
    }

    override def initChannel(ch: Channel): Unit = {
      super.initChannel(ch)
      context.foreach { sslCtx =>
        ch.pipeline().addFirst("ror_internode_ssl_handler", sslCtx.newHandler(ch.alloc()))
      }
    }

    private class SSLContextCreatorImpl extends SSLCertParser.SSLContextCreator {
      override def mkSSLContext(certChain: String, privateKey: String): Unit = {
        try { // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          val sslCtxBuilder = SslContextBuilder.forServer(
            new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
            null
          )
          // Cert verification enable by default for internode
          if (ssl.verifyClientAuth) sslCtxBuilder.clientAuth(ClientAuth.REQUIRE)
          logger.info("ROR Internode using SSL provider: " + SslContext.defaultServerProvider.name)
          SSLCertParser.validateProtocolAndCiphers(sslCtxBuilder.build.newEngine(ByteBufAllocator.DEFAULT), ssl)
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
