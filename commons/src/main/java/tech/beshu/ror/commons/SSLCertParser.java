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

package tech.beshu.ror.commons;

import cz.seznam.euphoria.shaded.guava.com.google.common.base.Joiner;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import javax.net.ssl.SSLEngine;
import java.io.FileInputStream;
import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Key;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * Created by sscarduzio on 02/07/2017.
 */
public class SSLCertParser {

  private final SSLContextCreator creator;
  private final LoggerShim logger;

  public SSLCertParser(BasicSettings.SSLSettings settings, LoggerShim logger, SSLContextCreator creator) {
    this.creator = creator;
    this.logger = logger;
    createContext(settings);
  }

  public static boolean validateProtocolAndCiphers(SSLEngine eng, LoggerShim logger, BasicSettings.SSLSettings basicSettings) {
    try {
      String[] defaultProtocols = eng.getEnabledProtocols();

      logger.info("ROR SSL: Available ciphers: " + Joiner.on(",").join(eng.getEnabledCipherSuites()));
      basicSettings.getAllowedSSLCiphers()
        .map(x -> x.toArray(new String[x.size()]))
        .ifPresent(p -> {
          eng.setEnabledCipherSuites(p);
          logger.info("ROR SSL: Restricting to ciphers: " + Joiner.on(",").join(eng.getEnabledCipherSuites()));
        });

      logger.info("ROR SSL: Available SSL protocols: " + Joiner.on(",").join(defaultProtocols));
      basicSettings.getAllowedSSLProtocols()
        .map(x -> x.toArray(new String[x.size()]))
        .ifPresent(p -> {
          eng.setEnabledProtocols(p);
          logger.info("ROR SSL: Restricting to SSL protocols: " + Joiner.on(",").join(eng.getEnabledProtocols()));
        });
      return true;
    } catch (Exception e) {
      logger.error("ROR SSL: cannot validate SSL protocols and ciphers! " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
      return false;
    }
  }

  private void createContext(BasicSettings.SSLSettings settings) {
    if (!settings.isSSLEnabled()) {
      logger.info("ROR SSL: SSL is disabled");
      return;
    }
    logger.info("ROR SSL: attempting with JKS keystore..");
    try {
      char[] keyStorePassBa = null;
      if (settings.getKeystorePass().isPresent()) {
        keyStorePassBa = settings.getKeystorePass().get().toCharArray();
      }

      // Load the JKS keystore
      final char[] finKeystoerPassBa = keyStorePassBa;
      java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

        try {
          String keystoreFile = settings.getKeystoreFile();
          ks.load(new FileInputStream(keystoreFile), finKeystoerPassBa);
        } catch (Exception e) {
          e.printStackTrace();
        }
        return null;
      });


      char[] keyPassBa = null;
      if (settings.getKeyPass().isPresent()) {
        keyPassBa = settings.getKeyPass().get().toCharArray();
      }

      // Get PrivKey from keystore
      String sslKeyAlias;
      if (!settings.getKeyAlias().isPresent()) {
        if (ks.aliases().hasMoreElements()) {
          String inferredAlias = ks.aliases().nextElement();
          logger.info("ROR SSL: ssl.key_alias not configured, took first alias in keystore: " + inferredAlias);
          sslKeyAlias = inferredAlias;
        }
        else {
          throw new SettingsMalformedException("No alias found, therefore key found in keystore!");
        }
      }
      else {
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
      logger.info("ROR SSL: Discovered key from JKS");

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
      logger.info("ROR SSL: Discovered cert chain from JKS");


      AccessController.doPrivileged(
        (PrivilegedAction<Void>) () -> {
          creator.mkSSLContext(certChain, privateKey);
          return null;
        });

    } catch (Throwable t) {
      logger.error("ROR SSL: Failed to load SSL certs and keys from JKS Keystore! " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
      if (t instanceof AccessControlException) {
        logger.error("ROR SSL: Check the JKS Keystore path is correct: " + settings.getKeystoreFile());
      }
      t.printStackTrace();
    }
  }

  public interface SSLContextCreator {
    void mkSSLContext(String certChain, String privateKey);
  }
}

