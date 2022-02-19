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

package tech.beshu.ror.es.ssl;

import io.netty.channel.*;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.Netty4Transport;
import tech.beshu.ror.configuration.SslConfiguration.InternodeSslConfiguration;
import tech.beshu.ror.utils.SSLCertHelper;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Optional;

public class SSLNetty4InternodeServerTransport extends Netty4Transport {

  private final Logger logger = LogManager.getLogger(this.getClass());
  private final InternodeSslConfiguration ssl;
  private final Boolean fipsCompliant;

  public SSLNetty4InternodeServerTransport(Settings settings,
                                           ThreadPool threadPool,
                                           PageCacheRecycler pageCacheRecycler,
                                           CircuitBreakerService circuitBreakerService,
                                           NamedWriteableRegistry namedWriteableRegistry,
                                           NetworkService networkService,
                                           InternodeSslConfiguration ssl,
                                           Boolean fipsCompliant) {
    super(settings, Version.CURRENT, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService);
    this.ssl = ssl;
    this.fipsCompliant = fipsCompliant;
  }

  @Override
  protected ChannelHandler getClientChannelInitializer(DiscoveryNode node) {

    return new Netty4Transport.ClientChannelInitializer() {

      @Override
      protected void initChannel(Channel ch) throws Exception {
        super.initChannel(ch);
        logger.info(">> internode SSL channel initializing");
        TrustManagerFactory usedTrustManager = ssl.certificateVerificationEnabled() ?
                SSLCertHelper.getTrustManagerFactory(ssl, fipsCompliant) : InsecureTrustManagerFactory.INSTANCE;

        SslContext sslCtx = SslContextBuilder.forClient()
                .trustManager(usedTrustManager)
                .build();

        ch.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
          @Override
          public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            SSLEngine sslEngine = sslCtx.newEngine(ctx.alloc());
            sslEngine.setUseClientMode(true);
            sslEngine.setNeedClientAuth(true);

            ctx.pipeline().replace(this, "internode_ssl_client", new SslHandler(sslEngine));
            super.connect(ctx, remoteAddress, localAddress, promise);
          }
        });
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof NotSslRecordException || (cause.getCause() != null && cause.getCause() instanceof NotSslRecordException)) {
          logger.error("Receiving non-SSL connections from: (" + ctx.channel().remoteAddress() + "). Will disconnect");
          ctx.channel().close();
        }
        else {
          super.exceptionCaught(ctx, cause);
        }
      }
    };
  }

  @Override
  public final ChannelHandler getServerChannelInitializer(String name) {
    return new SslChannelInitializer(name);
  }

  public class SslChannelInitializer extends ServerChannelInitializer {
    private Optional<SslContext> context = Optional.empty();

    public SslChannelInitializer(String name) {
      super(name);
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        context = Optional.of(SSLCertHelper.prepareSSLContext(ssl, fipsCompliant));
        return null;
      });
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
      super.initChannel(ch);

      context.ifPresent(sslCtx -> {
        ch.pipeline().addFirst("ror_internode_ssl_handler", sslCtx.newHandler(ch.alloc()));
      });
    }
  }

}

