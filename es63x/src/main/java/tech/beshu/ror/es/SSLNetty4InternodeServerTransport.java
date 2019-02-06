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

package tech.beshu.ror.es;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.Netty4Transport;
import tech.beshu.ror.commons.SSLCertParser;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import javax.net.ssl.SSLEngine;
import java.io.ByteArrayInputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class SSLNetty4InternodeServerTransport extends Netty4Transport {
  private final Environment environment;
  private final BasicSettings.SSLSettings sslSettings;
  private final LoggerShim logger;
  private boolean sslEnabled = true;

  public SSLNetty4InternodeServerTransport(Settings settings, ThreadPool threadPool, PageCacheRecycler pageCacheRecycler,
      CircuitBreakerService circuitBreakerService, NamedWriteableRegistry namedWriteableRegistry, NetworkService networkService, Environment environment) {
    super(settings, Version.CURRENT, threadPool, networkService, pageCacheRecycler, namedWriteableRegistry, circuitBreakerService);
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass(), getClass().getSimpleName()));
    this.environment = environment;

    BasicSettings basicSettings = BasicSettings.fromFileObj(
        logger,
        this.environment.configFile().toAbsolutePath(),
        settings
    );

    if (basicSettings.getSslInternodeSettings().isPresent()) {
      this.sslSettings = basicSettings.getSslInternodeSettings().get();
    }
    else {
      this.sslSettings = null;
    }
  }



  @Override
  protected ChannelHandler getClientChannelInitializer(DiscoveryNode node) {
    final boolean verifyHostname;

    return new Netty4Transport.ClientChannelInitializer() {

      @Override
      protected void initChannel(Channel ch) throws Exception {
        super.initChannel(ch);
        SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        ch.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
          @Override
          public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
            SSLEngine sslEngine = sslCtx.newEngine(ctx.alloc());
            sslEngine.setUseClientMode(true);
            ctx.pipeline().replace(this, "internode_ssl_client", new SslHandler(sslEngine));
            super.connect(ctx, remoteAddress, localAddress, promise);
          }
        });
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
      }
    };
  }

  @Override
  public final ChannelHandler getServerChannelInitializer(String name) {
    super.getServerChannelInitializer(name);
    if (sslEnabled) {
      return new SslChannelInitializer(name);
    }
    else {
      return super.getServerChannelInitializer(name);
    }
  }

  public class SslChannelInitializer extends ServerChannelInitializer {
    private Optional<SslContext> context = Optional.empty();

    public SslChannelInitializer(String name) {
      super(name);
      new SSLCertParser(sslSettings, logger, (certChain, privateKey) -> {
        try {
          // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(
              new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
              new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
              null
          );

          logger.info("ROR Internode SSL: Using SSL provider: " + SslContext.defaultServerProvider().name());
          SSLCertParser.validateProtocolAndCiphers(sslCtxBuilder.build().newEngine(ByteBufAllocator.DEFAULT), logger, sslSettings);

          sslSettings.getAllowedSSLCiphers().ifPresent(sslCtxBuilder::ciphers);

          sslSettings.getAllowedSSLProtocols()
                     .map(protoList -> protoList.toArray(new String[protoList.size()]))
                     .ifPresent(sslCtxBuilder::protocols);

          context = Optional.of(sslCtxBuilder.build());

        } catch (Exception e) {
          context = Optional.empty();
          logger.error("Failed to load SSL CertChain & private key from Keystore! "
              + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
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

