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

package tech.beshu.ror.unit.acl.definitions.ldaps.unboundid;

import tech.beshu.ror.settings.definitions.LdapSettings;

import java.time.Duration;
import java.util.Set;

public class ConnectionConfig {

  private final String host;
  private final int port;
  private final int poolSize;
  private final Duration connectionTimeout;
  private final Duration requestTimeout;
  private final boolean sslEnabled;
  private final boolean trustAllCerts;
  private final Set<String> servers;
  private LdapSettings.HA ha;

  public ConnectionConfig(
      String host,
      Set<String> servers,
      int port,
      int poolSize,
      Duration connectionTimeout,
      Duration requestTimeout,
      boolean sslEnabled,
      boolean trustAllCerts,
      LdapSettings.HA ha
  ) {

    this.host = host;
    this.servers = servers;
    this.port = port;
    this.poolSize = poolSize;
    this.connectionTimeout = connectionTimeout;
    this.requestTimeout = requestTimeout;
    this.sslEnabled = sslEnabled;
    this.trustAllCerts = trustAllCerts;
    this.ha = ha;
  }

  public String getHost() {
    return host;
  }

  public Set<String> getServers() {
    return servers;
  }

  public LdapSettings.HA getHA() {
    return this.ha;
  }

  public int getPort() {
    return port;
  }

  public int getPoolSize() {
    return poolSize;
  }

  public Duration getConnectionTimeout() {
    return connectionTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public boolean isSslEnabled() {
    return sslEnabled;
  }

  public boolean isTrustAllCerts() {
    return trustAllCerts;
  }

  public static class Builder {

    public static int DEFAULT_LDAP_PORT = 389;
    public static int DEFAULT_LDAP_CONNECTION_POOL_SIZE = 30;
    public static Duration DEFAULT_LDAP_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    public static Duration DEFAULT_LDAP_CONNECTION_TIMEOUT = Duration.ofSeconds(1);
    public static boolean DEFAULT_LDAP_SSL_ENABLED = true;
    public static boolean DEFAULT_LDAP_SSL_TRUST_ALL_CERTS = false;
    public static LdapSettings.HA DEFAULT_HA = LdapSettings.HA.FAILOVER;

    private final String host;
    private Set<String> servers;
    private int port = DEFAULT_LDAP_PORT;
    private int poolSize = DEFAULT_LDAP_CONNECTION_POOL_SIZE;
    private Duration requestTimeout = DEFAULT_LDAP_REQUEST_TIMEOUT;
    private Duration connectionTimeout = DEFAULT_LDAP_CONNECTION_TIMEOUT;
    private boolean sslEnabled = DEFAULT_LDAP_SSL_ENABLED;
    private boolean trustAllCerts = DEFAULT_LDAP_SSL_TRUST_ALL_CERTS;
    private LdapSettings.HA ha;

    public Builder(String host) {
      this.host = host;
    }

    public Builder setPort(int port) {
      this.port = port;
      return this;
    }

    public Builder setPoolSize(int poolSize) {
      this.poolSize = poolSize;
      return this;
    }

    public Builder setConnectionTimeout(Duration connectionTimeout) {
      this.connectionTimeout = connectionTimeout;
      return this;
    }

    public Builder setRequestTimeout(Duration requestTimeout) {
      this.requestTimeout = requestTimeout;
      return this;
    }

    public Builder setSslEnabled(boolean sslEnabled) {
      this.sslEnabled = sslEnabled;
      return this;
    }

    public Builder setTrustAllCerts(boolean trustAllCerts) {
      this.trustAllCerts = trustAllCerts;
      return this;
    }

    public Builder setServers(Set<String> serverSet) {
      this.servers = serverSet;
      return this;
    }

    public ConnectionConfig build() {
      return new ConnectionConfig(host, servers, port, poolSize, connectionTimeout, requestTimeout, sslEnabled, trustAllCerts, ha);
    }
  }
}
