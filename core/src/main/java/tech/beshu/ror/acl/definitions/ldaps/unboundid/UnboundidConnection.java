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

package tech.beshu.ror.acl.definitions.ldaps.unboundid;

import com.google.common.collect.Sets;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.FailoverServerSet;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RoundRobinServerSet;
import com.unboundid.ldap.sdk.ServerSet;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.SingleServerSet;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import io.jsonwebtoken.lang.Collections;
import tech.beshu.ror.acl.definitions.ldaps.LdapClientException;
import tech.beshu.ror.commons.settings.SettingsMalformedException;

import javax.net.ssl.SSLSocketFactory;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.Set;

public class UnboundidConnection {

  private LDAPConnectionPool connectionPool;

  public UnboundidConnection(ConnectionConfig connectionConfig,
      Optional<SearchingUserConfig> searchingUserConfig) {
    connect(connectionConfig, searchingUserConfig);
  }

  private static void checkConnectivity(SearchingUserConfig config, LDAPURL ldapServer, SSLSocketFactory sslSocketFactory,
      LDAPConnectionOptions options) throws LDAPException {

    LDAPConnection conn = ldapServer.getScheme().startsWith("ldaps") ?
        new SingleServerSet(ldapServer.getHost(), ldapServer.getPort(), sslSocketFactory, options).getConnection() :
        new SingleServerSet(ldapServer.getHost(), ldapServer.getPort(), options).getConnection();

    try {
      // Attempt connection to test the endpoint
      BindResult result = conn.bind(config.getDn(), config.getPassword());
      if (!ResultCode.SUCCESS.equals(result.getResultCode())) {
        throw new LdapClientException.InitializationException("LDAP binding problem - returned [" +
            result.getResultString() + "]");
      }
      System.out.println("checked " + ldapServer);
    } catch (LDAPException e) {
      throw new LdapClientException.InitializationException("LDAP binding problem", e);
    }
    finally {
      if (conn != null) {
        conn.close();
      }
    }
  }

  private void connect(ConnectionConfig connectionConfig, Optional<SearchingUserConfig> searchingUserConfig) {
    try {
      Set<LDAPURL> ldapServers = Sets.newHashSet();
      SSLUtil sslUtil = connectionConfig.isTrustAllCerts() ? new SSLUtil(new TrustAllTrustManager()) : new SSLUtil();
      SSLSocketFactory sslSocketFactory = sslUtil.createSSLSocketFactory();
      boolean isSecure;
      LDAPConnectionOptions options = new LDAPConnectionOptions();
      options.setConnectTimeoutMillis((int) connectionConfig.getConnectionTimeout().toMillis());
      options.setResponseTimeoutMillis(connectionConfig.getRequestTimeout().toMillis());
      ServerSet serverSet;

      // Parse multi-server
      if (!Collections.isEmpty(connectionConfig.getServers())) {
        int secureURIs = 0;
        int howManyServers = connectionConfig.getServers().size();
        String[] hosts = new String[howManyServers];
        int[] ports = new int[howManyServers];

        int iterations = 0;
        for (String serverURI : connectionConfig.getServers()) {
          try {
            LDAPURL serverConfig = new LDAPURL(serverURI);
            ldapServers.add(serverConfig);
            hosts[iterations] = serverConfig.getHost();
            ports[iterations] = serverConfig.getPort();
            if (serverConfig.getScheme().startsWith("ldaps")) {
              secureURIs++;
            }
            else {
              secureURIs--;
            }
          } catch (LDAPException e) {
            throw new SettingsMalformedException("cannot parse LDAP server: " + serverURI, e);
          }
          iterations++;
        }

        // VALIDATION
        if (Math.abs(secureURIs) != ldapServers.size()) {
          throw new SettingsMalformedException("The list of LDAP servers should be either all 'ldaps://' or all 'ldap://");
        }

        isSecure = secureURIs > 0;

        switch (connectionConfig.getHA()) {
          case ROUNDR_ROBIN: {
            serverSet = isSecure ?
                new RoundRobinServerSet(hosts, ports, sslSocketFactory, options) :
                new RoundRobinServerSet(hosts, ports, options);
            break;
          }

          default: {
            serverSet = isSecure ?
                new FailoverServerSet(hosts, ports, sslSocketFactory, options) :
                new FailoverServerSet(hosts, ports, options);
            break;
          }
        }
      }

      // Parse single server
      else {
        serverSet = connectionConfig.isSslEnabled() ?
            new SingleServerSet(connectionConfig.getHost(), connectionConfig.getPort(), sslSocketFactory, options) :
            new SingleServerSet(connectionConfig.getHost(), connectionConfig.getPort(), options);

        ldapServers.add(new LDAPURL(
            connectionConfig.isSslEnabled() ? "ldaps" : "ldap", connectionConfig.getHost(), connectionConfig.getPort(), null, null, null, null));
      }

      // Configure connection pool

      if (searchingUserConfig.isPresent()) {
        SearchingUserConfig config = searchingUserConfig.get();
        connectionPool = new LDAPConnectionPool(serverSet, new SimpleBindRequest(config.getDn(), config.getPassword()), connectionConfig.getPoolSize());

        for (LDAPURL lu : ldapServers) {
          checkConnectivity(config, lu, sslSocketFactory, options);
        }

      }
      else {
        connectionPool = new LDAPConnectionPool(serverSet, new SimpleBindRequest(), connectionConfig.getPoolSize());
      }

    } catch (GeneralSecurityException e) {
      throw new LdapClientException.InitializationException("SSL Factory creation problem", e);
    } catch (LDAPException e) {
      throw new LdapClientException.InitializationException("LDAP connection problem", e);
    }
  }

  public LDAPConnectionPool getConnectionPool() {
    return connectionPool;
  }

}
