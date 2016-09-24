package org.elasticsearch.plugin.readonlyrest;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.SecureRandom;

/**
 * @author <a href="mailto:scarduzio@gmail.com">Simone Scarduzio</a>
 * @see <a href="https://github.com/sscarduzio/elasticsearch-readonlyrest-plugin/">Github Project</a>
 * <p>
 * Created by sscarduzio on 23/09/2016.
 */

@Singleton
public class SSLEngineProvider {

  private final ConfigurationHelper conf;
  private SSLContext context = null;

  @Inject
  public SSLEngineProvider(ConfigurationHelper conf) throws Exception {
    this.conf = conf;
    System.out.println("SSL STATUS: " + conf.sslEnabled);
    if (conf.sslEnabled) {
      java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
      java.security.KeyStore ts = java.security.KeyStore.getInstance("JKS");

      ks.load(new java.io.FileInputStream(conf.sslKeyStoreFile), conf.sslKeyStorePassword.toCharArray());
      // ts.load(new java.io.FileInputStream(trustStoreFile), "".toCharArray());

      KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
      kmf.init(ks, conf.sslKeyPassword.toCharArray());

      TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
      tmf.init(ts);

      context = SSLContext.getInstance("TLS");
      context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
    }
  }

  public SSLContext getContext() throws Exception {
    if (conf.sslEnabled) {
      return context;
    } else throw new IllegalAccessException("SSL not enabled, cannot make a SSL engine");
  }
}
