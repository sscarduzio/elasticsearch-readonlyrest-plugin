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

package org.elasticsearch.plugin.readonlyrest.es.settings.ssl;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.EnabledSslSettings;
import org.elasticsearch.plugin.readonlyrest.settings.ssl.SslSettings;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.Key;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Optional;


public class SSLEngineProvider {

  private final Logger logger;
  private SslContext context;

  public SSLEngineProvider(SslSettings settings, ESContext esContext) {
    this.logger = esContext.logger(getClass());
    if (settings instanceof EnabledSslSettings) {
      createContext((EnabledSslSettings) settings);
    }
  }

  public Optional<SslContext> getContext() {
    return Optional.ofNullable(context);
  }

  private void createContext(EnabledSslSettings settings) {
    if (settings.getCertchainPem().isPresent() && settings.getPrivkeyPem().isPresent()) {
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        try {
          logger.info("Loading SSL context with certChain=" + settings.getCertchainPem().get().getName() +
              ", privKey=" + settings.getPrivkeyPem().get().getName());
          // #TODO expose configuration of sslPrivKeyPem password? Letsencrypt never sets one..
          context = SslContextBuilder.forServer(
              settings.getCertchainPem().get(),
              settings.getPrivkeyPem().get(),
              null
          ).build();
        } catch (SSLException e) {
          logger.error("Failed to load SSL CertChain & private key!");
          e.printStackTrace();
        }
        return null;
      });

      // Everything is configured
      logger.info("SSL configured through cert_chain and privkey");
      return;

    } else {
      logger.info("SSL cert_chain and privkey not configured, attempting with JKS keystore..");

      try {
        char[] keyStorePassBa = null;
        if (settings.getKeystorePass().isPresent()) {
          keyStorePassBa = settings.getKeystorePass().get().toCharArray();
        }

        // Load the JKS keystore
        java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
        ks.load(new java.io.FileInputStream(settings.getKeystoreFile()), keyStorePassBa);

        char[] keyPassBa = null;
        if (settings.getKeyPass().isPresent()) {
          keyPassBa = settings.getKeyPass().get().toCharArray();
        }

        // Get PrivKey from keystore
        String sslKeyAlias;
        if (!settings.getKeyAlias().isPresent()) {
          if (ks.aliases().hasMoreElements()) {
            String inferredAlias = ks.aliases().nextElement();
            logger.info("SSL ssl.key_alias not configured, took first alias in keystore: " + inferredAlias);
            sslKeyAlias = inferredAlias;
          } else {
            throw new SettingsMalformedException("No alias found, therefore key found in keystore!");
          }
        } else {
          sslKeyAlias = settings.getKeyAlias().get();
        }
        Key key = ks.getKey(sslKeyAlias, keyPassBa);
        if (key == null) {
          throw new SettingsMalformedException("Private key not found in keystore for alias: " + sslKeyAlias);
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
        Certificate[] cchain = ks.getCertificateChain(sslKeyAlias);

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
}
