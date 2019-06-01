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
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.__old_SSLCertParser;
import tech.beshu.ror.settings.__old_BasicSettings;
import tech.beshu.ror.shims.es.LoggerShim;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class SSLNetty4HttpServerTransport extends Netty4HttpServerTransport {
  private static final boolean DEFAULT_SSL_VERIFICATION_HTTP = false;
  private final __old_BasicSettings.SSLSettings sslSettings;
  private final LoggerShim logger;
  private final Environment environment;
  private  Boolean sslVerification = DEFAULT_SSL_VERIFICATION_HTTP;

  public SSLNetty4HttpServerTransport(Settings settings, NetworkService networkService, BigArrays bigArrays,
      ThreadPool threadPool, NamedXContentRegistry xContentRegistry, Dispatcher dispatcher, Environment environment) {
    super(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher);
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass(), getClass().getSimpleName()));

    this.environment = environment;
    __old_BasicSettings basicSettings = __old_BasicSettings.fromFileObj(
        logger,
        this.environment.configFile().toAbsolutePath(),
        settings
    );

    if (basicSettings.getSslHttpSettings().map(__old_BasicSettings.SSLSettings::isSSLEnabled).orElse(false)) {
      sslSettings = basicSettings.getSslHttpSettings().get();
      logger.info("creating SSL HTTP transport");
      this.sslVerification = sslSettings.isClientAuthVerify().orElse(DEFAULT_SSL_VERIFICATION_HTTP);

    }
    else {
      sslSettings = null;
    }
  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    if (!this.lifecycle.started()) {
      return;
    }
    if (cause.getCause() instanceof NotSslRecordException) {
      logger.warn(cause.getMessage() + " connecting from: " + ctx.channel().remoteAddress());
    }
    else {
      cause.printStackTrace();
      super.exceptionCaught(ctx, cause);
    }
    ctx.channel().flush().close();
  }

  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

  private class SSLHandler extends HttpChannelHandler {
    private Optional<SslContext> context = Optional.empty();

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLNetty4HttpServerTransport.this.detailedErrorsEnabled, SSLNetty4HttpServerTransport.this.threadPool.getThreadContext());

      new __old_SSLCertParser(sslSettings, (certChain, privateKey) -> {
        try {
          // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(
              new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
              new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
              null
          );

          logger.info("ROR SSL HTTP: Using SSL provider: " + SslContext.defaultServerProvider().name());
          __old_SSLCertParser.validateProtocolAndCiphers(sslCtxBuilder.build().newEngine(ByteBufAllocator.DEFAULT), sslSettings);

          sslSettings.getAllowedSSLCiphers().ifPresent(sslCtxBuilder::ciphers);

          if(sslVerification) {
            sslCtxBuilder.clientAuth(ClientAuth.REQUIRE);
          }

          sslSettings.getAllowedSSLProtocols()
                     .map(protoList -> protoList.toArray(new String[0]))
                     .ifPresent(sslCtxBuilder::protocols);

          context = Optional.of(sslCtxBuilder.build());

        } catch (Exception e) {
          context = Optional.empty();
          logger.error("Failed to load SSL HTTP CertChain & private key from Keystore! "
              + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
      });
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);
      context.ifPresent(sslCtx -> {
        ch.pipeline().addFirst("ssl_netty4_handler", sslCtx.newHandler(ch.alloc()));
      });
    }
  }
}
