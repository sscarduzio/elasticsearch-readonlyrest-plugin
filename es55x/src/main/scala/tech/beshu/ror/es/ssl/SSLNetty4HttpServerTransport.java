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
import io.netty.handler.ssl.SslHandler;
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;

public class SSLNetty4HttpServerTransport extends Netty4HttpServerTransport {

  private final Logger logger = LogManager.getLogger(this.getClass());
  private final ExternalSslConfiguration ssl;

  private SslContext sslContext;

  public SSLNetty4HttpServerTransport(Settings settings,
                                      NetworkService networkService,
                                      BigArrays bigArrays,
                                      ThreadPool threadPool,
                                      NamedXContentRegistry xContentRegistry,
                                      Dispatcher dispatcher,
                                      ExternalSslConfiguration ssl) {
    super(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher);
    this.ssl = ssl;
    SSLCertParser.run(new SSLContextCreatorImpl(ssl), ssl);
  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    if (!this.lifecycle.started()) {
      return;
    }
    if (cause.getCause() instanceof NotSslRecordException || cause.getCause() instanceof SSLHandshakeException) {
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

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLNetty4HttpServerTransport.this.detailedErrorsEnabled, SSLNetty4HttpServerTransport.this.threadPool.getThreadContext());
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);

      SSLEngine eng = sslContext.newEngine(ch.alloc());

      if(ssl.allowedCiphers().size() > 0) {
        eng.setEnabledCipherSuites(
            JavaConverters$.MODULE$
                .setAsJavaSet(ssl.allowedCiphers())
                .stream()
                .map(SslConfiguration.Cipher::value)
                .toArray(String[]::new)
        );
      }

      if(ssl.allowedProtocols().size() > 0) {
        eng.setEnabledProtocols(
            JavaConverters$.MODULE$
                .setAsJavaSet(ssl.allowedProtocols())
                .stream()
                .map(SslConfiguration.Protocol::value)
                .toArray(String[]::new)
        );
      }

      ch.pipeline().addFirst("ssl_netty4_handler", new SslHandler(eng));

    }
  }

  private class SSLContextCreatorImpl implements SSLCertParser.SSLContextCreator {

    private final SslConfiguration sslConfiguration;

    public SSLContextCreatorImpl(SslConfiguration sslConfiguration) {
      this.sslConfiguration = sslConfiguration;
    }

    @Override
    public void mkSSLContext(InputStream certChain, InputStream privateKey) {
      try {
        // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
        SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(certChain, privateKey, null);

        if (ssl.clientAuthenticationEnabled()) {
          sslCtxBuilder.clientAuth(ClientAuth.REQUIRE);
          TrustManagerFactory usedTrustManager = SSLCertParser.customTrustManagerFrom(ssl).getOrElse(null);
          sslCtxBuilder.trustManager(usedTrustManager);
        }

        // Creating one SSL engine just for protocol/cipher validation and logging
        sslContext = sslCtxBuilder.build();
        SSLEngine eng = sslContext.newEngine(ByteBufAllocator.DEFAULT);

        logger.info("ROR SSL: Using SSL provider: " + SslContext.defaultServerProvider().name());
        SSLCertParser.validateProtocolAndCiphers(eng, sslConfiguration);
      } catch (Exception e) {
        logger.error("Failed to load SSL CertChain & private key from Keystore! "
            + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
      }
    }
  }
}
