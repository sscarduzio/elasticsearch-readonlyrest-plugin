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

/**
 * Created by sscarduzio on 28/11/2016.
 */
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
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
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.utils.SSLCertParser;
import tech.beshu.ror.configuration.SslConfiguration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Collectors;

public class SSLNetty4HttpServerTransport extends Netty4HttpServerTransport {

  private final SslConfiguration ssl;
  private final Logger logger = LogManager.getLogger(this.getClass());

  public SSLNetty4HttpServerTransport(Settings settings, NetworkService networkService, BigArrays bigArrays,
      ThreadPool threadPool, NamedXContentRegistry xContentRegistry, Dispatcher dispatcher, SslConfiguration ssl) {
    super(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher);
    this.ssl = ssl;
  }

  @Override
  protected void onException(HttpChannel channel, Exception cause) {
    if (!this.lifecycle.started()) {
      return;
    }
    if (cause.getCause() instanceof NotSslRecordException) {
      logger.warn(cause.getMessage() + " connecting from: " + channel.getRemoteAddress());
    }
    else {
      super.onException(channel, cause);
    }
    channel.close();
  }

  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

  private class SSLHandler extends Netty4HttpServerTransport.HttpChannelHandler {
    private Optional<SslContext> context = Optional.empty();

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, handlingSettings);

      new SSLCertParser(ssl, (certChain, privateKey) -> {
        try {
          // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(
              new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
              new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
              null
          );

          logger.info("ROR SSL HTTP: Using SSL provider: " + SslContext.defaultServerProvider().name());
          SSLCertParser.validateProtocolAndCiphers(sslCtxBuilder.build().newEngine(ByteBufAllocator.DEFAULT), ssl);

          if(ssl.allowedCiphers().size() > 0) {
            sslCtxBuilder.ciphers(
                ssl.allowedCiphers().stream().map(SslConfiguration.Cipher::value).collect(Collectors.toList())
            );
          }

          if (ssl.verifyClientAuth()) {
            sslCtxBuilder.clientAuth(ClientAuth.REQUIRE);
          }

          if(ssl.allowedProtocols().size() > 0) {
            sslCtxBuilder.protocols(
                ssl.allowedProtocols().stream().map(SslConfiguration.Protocol::value).toArray(String[]::new)
            );
          }

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
