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
