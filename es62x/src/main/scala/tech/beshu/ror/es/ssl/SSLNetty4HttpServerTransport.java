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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.threadpool.ThreadPool;
import scala.collection.JavaConverters$;
import tech.beshu.ror.configuration.SslConfiguration;
import tech.beshu.ror.configuration.SslConfiguration.ExternalSslConfiguration;
import tech.beshu.ror.utils.SSLCertParser;

import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Optional;
import java.util.stream.Collectors;

public class SSLNetty4HttpServerTransport extends Netty4HttpServerTransport {

  private final Logger logger = LogManager.getLogger(this.getClass());
  private final ExternalSslConfiguration ssl;

  public SSLNetty4HttpServerTransport(Settings settings,
                                      NetworkService networkService,
                                      BigArrays bigArrays,
                                      ThreadPool threadPool,
                                      NamedXContentRegistry xContentRegistry,
                                      Dispatcher dispatcher,
                                      ExternalSslConfiguration ssl) {
    super(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher);
    this.ssl = ssl;
  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    if (!this.lifecycle.started()) {
      return;
    }
    if (cause.getCause() instanceof NotSslRecordException) {
      logger.warn(cause.getMessage() + " connecting from: " + ctx.channel().remoteAddress());
    }
    else {
      super.exceptionCaught(ctx, cause);
    }
    ctx.channel().flush().close();
  }

  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

  private class SSLHandler extends Netty4HttpServerTransport.HttpChannelHandler {
    private Optional<SslContext> context = Optional.empty();

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLNetty4HttpServerTransport.this.detailedErrorsEnabled, SSLNetty4HttpServerTransport.this.threadPool.getThreadContext());
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        SSLCertParser.run(new SSLContextCreatorImpl(), ssl);
        return null;
      });
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);
      context.ifPresent(sslCtx -> {
        ch.pipeline().addFirst("ssl_netty4_handler", sslCtx.newHandler(ch.alloc()));
      });
    }

    private class SSLContextCreatorImpl implements SSLCertParser.SSLContextCreator {
      @Override
      public void mkSSLContext(InputStream certChain, InputStream privateKey) {
        try {
          // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(certChain, privateKey, null);

          logger.info("ROR SSL HTTP: Using SSL provider: " + SslContext.defaultServerProvider().name());
          SSLCertParser.validateProtocolAndCiphers(sslCtxBuilder.build().newEngine(ByteBufAllocator.DEFAULT), ssl);

          if(ssl.allowedCiphers().size() > 0) {
            sslCtxBuilder.ciphers(
                JavaConverters$.MODULE$
                    .setAsJavaSet(ssl.allowedCiphers())
                    .stream()
                    .map(SslConfiguration.Cipher::value)
                    .collect(Collectors.toList())
            );
          }

          if (ssl.clientAuthenticationEnabled()) {
            sslCtxBuilder.clientAuth(ClientAuth.REQUIRE);
            TrustManagerFactory usedTrustManager = SSLCertParser.customTrustManagerFrom(ssl).getOrElse(null);
            sslCtxBuilder.trustManager(usedTrustManager);
          }

          if(ssl.allowedProtocols().size() > 0) {
            sslCtxBuilder.protocols(
                JavaConverters$.MODULE$
                    .setAsJavaSet(ssl.allowedProtocols())
                    .stream()
                    .map(SslConfiguration.Protocol::value)
                    .toArray(String[]::new)
            );
          }

          context = Optional.of(sslCtxBuilder.build());

        } catch (Exception e) {
          context = Optional.empty();
          logger.error("Failed to load SSL HTTP CertChain & private key from Keystore! "
              + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
      }
    }
  }
}
