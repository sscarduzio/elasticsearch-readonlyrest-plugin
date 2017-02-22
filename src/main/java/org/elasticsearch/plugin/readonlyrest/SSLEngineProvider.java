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

package org.elasticsearch.plugin.readonlyrest;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.Key;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.Base64;


@Singleton
public class SSLEngineProvider {
  public final ConfigurationHelper conf;
  private final Logger logger = Loggers.getLogger(this.getClass());
  private SslContext context = null;

  @Inject
  public SSLEngineProvider(Settings s) {
    this.conf = ConfigurationHelper.parse(s);
    if (conf.sslEnabled) {
      if (!Strings.isNullOrEmpty(conf.sslCertChainPem) && !Strings.isNullOrEmpty(conf.sslPrivKeyPem)) {
        AccessController.doPrivileged(
          new PrivilegedAction<Void>() {
            @Override
            public Void run() {
              try {
                logger.info("Loading SSL context with certChain=" + conf.sslCertChainPem + ", privKey=" + conf.sslPrivKeyPem);
                // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
                context = SslContextBuilder.forServer(
                  new File(conf.sslCertChainPem),
                  new File(conf.sslPrivKeyPem),
                  null
                ).build();
              } catch (SSLException e) {
                logger.error("Failed to load SSL CertChain & private key!");
                e.printStackTrace();
              }
              return null;
            }
          });

        // Everything is configured
        logger.info("SSL configured through cert_chain and privkey");
        return;
      }

      logger.info("SSL cert_chain and privkey not configured, attempting with JKS keystore..");

      try {
        char[] keyStorePassBa = null;
        if (!Strings.isNullOrEmpty(conf.sslKeyStorePassword)) {
          keyStorePassBa = conf.sslKeyStorePassword.toCharArray();
        }

        // Load the JKS keystore
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(new java.io.FileInputStream(conf.sslKeyStoreFile), keyStorePassBa);

        char[] keyPassBa = null;
        if (!Strings.isNullOrEmpty(conf.sslKeyPassword)) {
          keyPassBa = conf.sslKeyPassword.toCharArray();
        }

        // Get PrivKey from keystore
        if (Strings.isNullOrEmpty(conf.sslKeyAlias)) {
          if (ks.aliases().hasMoreElements()) {
            String inferredAlias = ks.aliases().nextElement();
            logger.info("SSL ssl.key_alias not configured, took first alias in keystore: " + inferredAlias);
            conf.sslKeyAlias = inferredAlias;
          }
          else {
            throw new ElasticsearchException("No alias found, therefore key found in keystore!");
          }
        }
        Key key = ks.getKey(conf.sslKeyAlias, keyPassBa);
        if (key == null) {
          throw new ElasticsearchException("Private key not found in keystore for alias: " + conf.sslKeyAlias);
        }

        // Create a PEM of the private key
        StringBuilder sb = new StringBuilder();
        sb.append("---BEGIN PRIVATE KEY---\n");
        sb.append(Base64.getEncoder().encodeToString(key.getEncoded()));
        sb.append("\n");
        sb.append("---END PRIVATE KEY---");
        String privateKey = sb.toString();
        logger.info("Discovered key from JKS");

        // Get CertChain from keystore
        Certificate[] cchain = ks.getCertificateChain(conf.sslKeyAlias);

        // Create a PEM of the certificate chain
        sb = new StringBuilder();
        for (Certificate c : cchain) {
          sb.append("-----BEGIN CERTIFICATE-----\n");
          sb.append(Base64.getEncoder().encodeToString(c.getEncoded()));
          sb.append("\n");
          sb.append("-----END CERTIFICATE-----\n");
        }
        String certChain = sb.toString();
        logger.info("Discovered cert chain from JKS");


        AccessController.doPrivileged(
          new PrivilegedAction<Void>() {
            @Override
            public Void run() {
              try {
                // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
                context = SslContextBuilder.forServer(
                  new ByteArrayInputStream(certChain.getBytes(StandardCharsets.UTF_8)),
                  new ByteArrayInputStream(privateKey.getBytes(StandardCharsets.UTF_8)),
                  null
                ).build();
              } catch (Exception e) {
                logger.error("Failed to load SSL CertChain & private key from Keystore!");
                e.printStackTrace();
              }
              return null;
            }
          });

      } catch (Throwable t) {
        logger.error("Failed to load SSL certs and keys from JKS Keystore!");
        t.printStackTrace();
      }

    }
  }

  public SslContext getContext() throws Exception {
    if (conf.sslEnabled) {
      return context;
    }
    else throw new IllegalAccessException("SSL not enabled, cannot make a SSL engine");
  }
}
