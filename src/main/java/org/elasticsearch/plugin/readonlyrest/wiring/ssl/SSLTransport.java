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

package org.elasticsearch.plugin.readonlyrest.wiring.ssl;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.netty.NettyHttpServerTransport;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.handler.ssl.SslContext;

/**
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 * <p>
 * Created by sscarduzio on 23/09/2016.
 */
public class SSLTransport extends NettyHttpServerTransport {

  private final SSLContextProvider sslContextProvider;
  protected ConfigurationHelper conf;

  @Inject
  public SSLTransport(Settings settings, SSLContextProvider contextProvider, NetworkService networkService, BigArrays bigArrays, ConfigurationHelper conf) {
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
        SslContext sslCtx = sslContextProvider.getContext();
        pipeline.addFirst("ssl_netty3_handler", sslCtx.newHandler());
      }
      return pipeline;
    }
  }
}
