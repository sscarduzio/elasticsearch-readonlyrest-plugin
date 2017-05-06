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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid;

import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientException;

import javax.net.ssl.SSLSocketFactory;
import java.security.AccessController;
import java.security.GeneralSecurityException;
import java.security.PrivilegedAction;
import java.util.Optional;

public class UnboundidConnection {

  private LDAPConnectionPool connectionPool;

  public UnboundidConnection(ConnectionConfig connectionConfig,
      Optional<SearchingUserConfig> searchingUserConfig) {
    connect(connectionConfig, searchingUserConfig);
  }

  private void connect(ConnectionConfig connectionConfig, Optional<SearchingUserConfig> searchingUserConfig) {
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis((int) connectionConfig.getConnectionTimeout().toMillis());
        options.setResponseTimeoutMillis(connectionConfig.getRequestTimeout().toMillis());
        LDAPConnection connection;
        if (connectionConfig.isSslEnabled()) {
          SSLUtil sslUtil = connectionConfig.isTrustAllCerts() ? new SSLUtil(new TrustAllTrustManager()) : new SSLUtil();
          SSLSocketFactory sslSocketFactory = sslUtil.createSSLSocketFactory();
          connection = new LDAPConnection(sslSocketFactory, options);
        }
        else {
          connection = new LDAPConnection(options);
        }
        connection.connect(
            connectionConfig.getHost(),
            connectionConfig.getPort(),
            (int) connectionConfig.getConnectionTimeout().toMillis()
        );
        searchingUserConfig.ifPresent(config -> {
          try {
            BindResult result = connection.bind(config.getDn(), config.getPassword());
            if (!ResultCode.SUCCESS.equals(result.getResultCode())) {
              throw new LdapClientException.InitializationException("LDAP binding problem - returned [" +
                  result.getResultString() + "]");
            }
          } catch (LDAPException e) {
            throw new LdapClientException.InitializationException("LDAP binding problem", e);
          }
        });
        connectionPool = new LDAPConnectionPool(connection, connectionConfig.getPoolSize());
      } catch (GeneralSecurityException e) {
        throw new LdapClientException.InitializationException("SSL Factory creation problem", e);
      } catch (LDAPException e) {
        throw new LdapClientException.InitializationException("LDAP connection problem", e);
      }
      return null;
    });
  }

  public LDAPConnectionPool getConnectionPool() {
    return connectionPool;
  }

}
