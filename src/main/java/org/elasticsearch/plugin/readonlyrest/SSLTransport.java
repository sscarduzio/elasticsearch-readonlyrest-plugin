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

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.netty.NettyHttpServerTransport;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/**
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 * <p>
 * Created by sscarduzio on 23/09/2016.
 */
public class SSLTransport extends NettyHttpServerTransport {

  private final SSLEngineProvider sslContextProvider;
  protected ConfigurationHelper conf;

  @Inject
  public SSLTransport(Settings settings, SSLEngineProvider contextProvider, NetworkService networkService, BigArrays bigArrays, ConfigurationHelper conf) {
    super(settings, networkService, bigArrays);
    this.conf = conf;
    this.sslContextProvider = contextProvider;
  }

  @Override
  public ChannelPipelineFactory configureServerChannelPipelineFactory() {
    return new HttpSslChannelPipelineFactory(this);
  }

  private class HttpSslChannelPipelineFactory extends HttpChannelPipelineFactory {

    public HttpSslChannelPipelineFactory(NettyHttpServerTransport transport) {
      super(transport, true);
    }

    public ChannelPipeline getPipeline() throws Exception {
      ChannelPipeline pipeline = super.getPipeline();
      if (conf.sslEnabled) {
        SSLContext sslCtx = sslContextProvider.getContext();
        SSLEngine sslEngine = sslCtx.createSSLEngine();
        sslEngine.setUseClientMode(false);
        sslEngine.setEnableSessionCreation(true);
        sslEngine.setWantClientAuth(true);
        pipeline.addFirst("ssl_handler", new SslHandler(sslEngine));
      }
      return pipeline;
    }
  }
}
