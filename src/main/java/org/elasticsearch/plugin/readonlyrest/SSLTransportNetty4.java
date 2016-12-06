/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 28/11/2016.
 */

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslHandler;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.threadpool.ThreadPool;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class SSLTransportNetty4 extends Netty4HttpServerTransport {

  private final SSLEngineProvider engineProvider;
  protected ConfigurationHelper conf;

  @Inject
  public SSLTransportNetty4(final Settings settings, final NetworkService networkService,
                            final BigArrays bigArrays, final ThreadPool threadPool,
                            SSLEngineProvider engineProvider, ConfigurationHelper conf) {
    super(settings, networkService, bigArrays, threadPool);
    System.out.println("OMG CREATING SSL TRANSPORT!!!!!!!!!!!!!!!!!");
    this.engineProvider = engineProvider;
    this.conf = conf;
  }

  protected void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    System.out.println("ZOMG EXCEPTION IN SSL TRANSPORT!!!!!!!!!!!!!!!!!");
    cause.printStackTrace();
    if (!this.lifecycle.started()) {
      return;
    }
  }

//  protected void doStart() {
//    super.doStart();
//  }

  public ChannelHandler configureServerChannelHandler() {
    return new SSLHandler(this);
  }

//  public static void overrideSettings(final Settings.Builder settingsBuilder, final Settings settings) {
//    settingsBuilder.put(HttpTransportSettings.SETTING_HTTP_COMPRESSION.getKey(), false);
//  }

  private class SSLHandler extends Netty4HttpServerTransport.HttpChannelHandler {

    SSLHandler(final Netty4HttpServerTransport transport) {
      super(transport, SSLTransportNetty4.this.detailedErrorsEnabled, SSLTransportNetty4.this.threadPool.getThreadContext());
    }

    protected void initChannel(final Channel ch) throws Exception {
      super.initChannel(ch);
      System.out.println("INITIALIZING SSL CHANNEL !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
      if (conf.sslEnabled) {
        SSLContext sslCtx = engineProvider.getContext();
        SSLEngine sslEngine = sslCtx.createSSLEngine();
        sslEngine.setUseClientMode(false);
        sslEngine.setEnableSessionCreation(true);
        sslEngine.setWantClientAuth(true);
        ch.pipeline().addFirst("ssl_netty4_handler", new SslHandler(sslEngine));
      }
    }
  }
}
