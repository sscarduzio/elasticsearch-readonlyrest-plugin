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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.__old_SSLCertParser;
import tech.beshu.ror.settings.__old_BasicSettings;
import tech.beshu.ror.shims.es.LoggerShim;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class SSLTransportNetty4 extends Netty4HttpServerTransport {

  private final LoggerShim logger;
  private SslContext sslContext;
  private __old_BasicSettings.SSLSettings sslSettings;

  public SSLTransportNetty4(Settings settings, NetworkService networkService, BigArrays bigArrays,
      ThreadPool threadPool, __old_BasicSettings.SSLSettings sslSettings) {
    super(settings, networkService, bigArrays, threadPool);
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass().getName()));
    logger.info("creating SSL transport");
    this.sslSettings = sslSettings;

    new __old_SSLCertParser(sslSettings, (certChain, privateKey) -> {

      try {
        // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
        SslContextBuilder sslcb = SslContextBuilder.forServer(
            new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
            null
        );

        // Creating one SSL engine just for protocol/cipher validation and logging
        sslContext = sslcb.build();
        SSLEngine eng = sslContext.newEngine(ByteBufAllocator.DEFAULT);

        logger.info("ROR SSL: Using SSL provider: " + SslContext.defaultServerProvider().name());
        __old_SSLCertParser.validateProtocolAndCiphers(eng, sslSettings);

      } catch (Exception e) {
        logger.error("Failed to load SSL CertChain & private key from Keystore! "
            + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
      }
    });

  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {

    if (!this.lifecycle.started()) {
      return;
    }
    if (cause.getCause() instanceof NotSslRecordException || cause.getCause() instanceof SSLHandshakeException) {
      logger.warn(cause.getMessage());
    }

    else {
      cause.printStackTrace();
      super.exceptionCaught(ctx, cause);
    }
    ctx.channel().flush().close();
  }

  @Override
  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

  private class SSLHandler extends Netty4HttpServerTransport.HttpChannelHandler {

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLTransportNetty4.this.detailedErrorsEnabled, SSLTransportNetty4.this.threadPool.getThreadContext());
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);
      SSLEngine eng = sslContext.newEngine(ch.alloc());

      sslSettings.getAllowedSSLProtocols()
                 .ifPresent(p -> eng.setEnabledProtocols(p.toArray(new String[0])));
      sslSettings.getAllowedSSLCiphers()
                 .ifPresent(c -> eng.setEnabledCipherSuites(c.toArray(new String[0])));

      ch.pipeline().addFirst("ssl_netty4_handler", new SslHandler(eng));

    }
  }
}
