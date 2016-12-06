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
import org.elasticsearch.common.inject.Singleton;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.SecureRandom;


@Singleton
public class SSLEngineProvider {

  private final ConfigurationHelper conf;
  private SSLContext context = null;

  @Inject
  public SSLEngineProvider(ConfigurationHelper conf) throws Exception {
    this.conf = conf;
    System.out.println("SSL STATUS: " + conf.sslEnabled);
    if (conf.sslEnabled) {
      try {
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
      } catch (Throwable t) {
        System.out.println("CANNOT INIT SSL!!!!!!!!!!!!");
        t.printStackTrace();
      }
    }
  }

  public SSLContext getContext() throws Exception {
    if (conf.sslEnabled) {
      return context;
    } else throw new IllegalAccessException("SSL not enabled, cannot make a SSL engine");
  }
}
