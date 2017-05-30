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

package org.elasticsearch.plugin.readonlyrest.es;

/**
 * Created by sscarduzio on 28/11/2016.
 */

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.NotSslRecordException;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.es.settings.ssl.ESSslSettings;
import org.elasticsearch.plugin.readonlyrest.es.settings.ssl.SSLEngineProvider;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;
import org.elasticsearch.threadpool.ThreadPool;

public class SSLTransportNetty4 extends Netty4HttpServerTransport {

  private final ESContext esContext;
  private final SslSettings sslSettings;

  public SSLTransportNetty4(ESContext esContext, Settings settings, final NetworkService networkService,
                            final BigArrays bigArrays, final ThreadPool threadPool,
                            final NamedXContentRegistry namedXContentRegistry) {
    super(settings, networkService, bigArrays, threadPool, namedXContentRegistry);
    this.esContext = esContext;
    this.sslSettings = ESSslSettings.from(settings);
    logger.info("creating SSL transport");

  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    if (!this.lifecycle.started()) {
      return;
    }
    if(cause.getCause() instanceof NotSslRecordException){
      logger.warn(cause.getMessage());
    }
    else {
      logger.error("exception in SSL transport: " + cause.getMessage());
      cause.printStackTrace();
      super.exceptionCaught(ctx, cause);
    }
    ctx.channel().flush().close();
  }

  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

  private class SSLHandler extends HttpChannelHandler {
    private SSLEngineProvider engineProvider = new SSLEngineProvider(sslSettings, esContext);

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLTransportNetty4.this.detailedErrorsEnabled, SSLTransportNetty4.this.threadPool.getThreadContext());
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);
      engineProvider.getContext().ifPresent(sslCtx -> {
        ch.pipeline().addFirst("ssl_netty4_handler", sslCtx.newHandler(ch.alloc()));
      });
    }
  }
}
