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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClientWithCacheDecorator;
import org.elasticsearch.plugin.readonlyrest.ldap.UnboundidLdapClient;

import java.time.Duration;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.ldap.LdapClientWithLoggingDecorator.wrapInLoggingIfIsLoggingEnabled;

public class LdapConfig {

  private static String ATTRIBUTE_NAME = "name";
  private static String ATTRIBUTE_HOST = "host";
  private static String ATTRIBUTE_PORT = "port";
  private static String ATTRIBUTE_BIND_DN = "bind_dn";
  private static String ATTRIBUTE_BIND_PASSWORD = "bind_password";
  private static String ATTRIBUTE_SEARCH_USER_BASE_DN = "search_user_base_DN";
  private static String ATTRIBUTE_SEARCH_GROUPS_BASE_DN = "search_groups_base_DN";
  private static String ATTRIBUTE_UID_ATTRIBUTE = "user_id_attribute";
  private static String ATTRIBUTE_UNIQUE_MEMEBER_ATTRIBUTE = "unique_member_attribute";
  private static String ATTRIBUTE_CONNECTION_POOL_SIZE = "connection_pool_size";
  private static String ATTRIBUTE_CONNECTION_TIMEOUT = "connection_timeout_in_sec";
  private static String ATTRIBUTE_REQUEST_TIMEOUT = "request_timeout_in_sec";
  private static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";
  private static String ATTRIBUTE_SSL_ENABLED = "ssl_enabled";
  private static String ATTRIBUTE_SSL_TRUST_ALL_CERTS = "ssl_trust_all_certs";

  private final String name;
  private final LdapClient client;

  private LdapConfig(String name, LdapClient client) {
    this.name = name;
    this.client = client;
  }

  public static LdapConfig fromSettings(Settings settings) throws ConfigMalformedException {
    String name = nameFrom(settings);
    UnboundidLdapClient.Builder builder =
      new UnboundidLdapClient.Builder(
        hostFrom(settings, name),
        searchUserBaseDnFrom(settings, name),
        searchGroupsBaseDnFrom(settings, name)
      )
        .setPort(portFrom(settings))
        .setSslEnabled(sslEnabledFrom(settings))
        .setTrustAllCerts(trustAllCertsFrom(settings))
        .setPoolSize(poolSizeFrom(settings))
        .setConnectionTimeout(connectionTimeoutFrom(settings))
        .setRequestTimeout(requestTimeoutFrom(settings))
        .setUidAttribute(uidAttribute(settings))
        .setUniqueMemberAttribute(uniqueMemberAttribute(settings));

    Optional<UnboundidLdapClient.BindDnPassword> bindDnPassword = bindDNPasswordFrom(settings);
    Duration cacheTtl = cacheTtlFrom(settings);

    LdapClient client = bindDnPassword.map(bdnp ->
                                             builder.setBindDnPassword(bdnp).build())
      .orElseGet(builder::build);

    return new LdapConfig(
        name,
        wrapInLoggingIfIsLoggingEnabled(name,
            cacheTtl.isZero() ? client : new LdapClientWithCacheDecorator(client, cacheTtl))
    );
  }

  private static String nameFrom(Settings settings) {
    String name = settings.get(ATTRIBUTE_NAME);
    if (name == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_NAME +
                                                           "] attribute");
    return name;
  }

  private static String hostFrom(Settings settings, String name) {
    String host = settings.get(ATTRIBUTE_HOST);
    if (host == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_HOST +
                                                           "] attribute defined for LDAP [" + name + "]");
    return host;
  }

  private static boolean sslEnabledFrom(Settings settings) {
    return settings.getAsBoolean(ATTRIBUTE_SSL_ENABLED, UnboundidLdapClient.Builder.DEFAULT_LDAP_SSL_ENABLED);
  }

  private static boolean trustAllCertsFrom(Settings settings) {
    return settings.getAsBoolean(ATTRIBUTE_SSL_TRUST_ALL_CERTS, UnboundidLdapClient.Builder.DEFAULT_LDAP_SSL_TRUST_ALL_CERTS);
  }

  private static Optional<UnboundidLdapClient.BindDnPassword> bindDNPasswordFrom(Settings settings) {
    Optional<String> bindDn = Optional.ofNullable(settings.get(ATTRIBUTE_BIND_DN));
    Optional<String> bindPassword = Optional.ofNullable(settings.get(ATTRIBUTE_BIND_PASSWORD));
    Optional<UnboundidLdapClient.BindDnPassword> bindDnPassword;
    if (bindDn.isPresent() && bindPassword.isPresent()) {
      bindDnPassword = Optional.of(new UnboundidLdapClient.BindDnPassword(bindDn.get(), bindPassword.get()));
    }
    else if (!bindDn.isPresent() && !bindPassword.isPresent()) {
      bindDnPassword = Optional.empty();
    }
    else {
      throw new ConfigMalformedException("LDAP definition malformed - must configure both params [" +
                                           ATTRIBUTE_BIND_DN + ", " + ATTRIBUTE_BIND_PASSWORD + "]");
    }
    return bindDnPassword;
  }

  private static String searchUserBaseDnFrom(Settings settings, String name) {
    String searchUserBaseDn = settings.get(ATTRIBUTE_SEARCH_USER_BASE_DN);
    if (searchUserBaseDn == null) {
      throw new ConfigMalformedException(
        "LDAP definition malformed - no [" + ATTRIBUTE_SEARCH_USER_BASE_DN +
          "] attribute defined for LDAP [" + name + "]");
    }
    return searchUserBaseDn;
  }

  private static String searchGroupsBaseDnFrom(Settings settings, String name) {
    String searchGroupsBaseDn = settings.get(ATTRIBUTE_SEARCH_GROUPS_BASE_DN);
    if (searchGroupsBaseDn == null) {
      throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_SEARCH_GROUPS_BASE_DN +
                                           "] attribute defined for LDAP [" + name + "]");
    }
    return searchGroupsBaseDn;
  }

  private static String uidAttribute(Settings settings) {
    return settings.get(ATTRIBUTE_UID_ATTRIBUTE, UnboundidLdapClient.Builder.DEFAULT_UID_ATTRIBUTE);
  }

  private static String uniqueMemberAttribute(Settings settings) {
    return settings.get(ATTRIBUTE_UNIQUE_MEMEBER_ATTRIBUTE, UnboundidLdapClient.Builder.DEFAULT_UNIQUE_MEMBER_ATTRIBUTE);
  }

  private static int portFrom(Settings settings) {
    return settings.getAsInt(ATTRIBUTE_PORT, UnboundidLdapClient.Builder.DEFAULT_LDAP_PORT);
  }

  private static int poolSizeFrom(Settings settings) {
    return settings.getAsInt(
      ATTRIBUTE_CONNECTION_POOL_SIZE,
      UnboundidLdapClient.Builder.DEFAULT_LDAP_CONNECTION_POOL_SIZE
    );
  }

  private static Duration connectionTimeoutFrom(Settings settings) {
    return Duration.ofSeconds(settings.getAsLong(
      ATTRIBUTE_CONNECTION_TIMEOUT,
      UnboundidLdapClient.Builder.DEFAULT_LDAP_CONNECTION_TIMEOUT.getSeconds()
    ));
  }

  private static Duration requestTimeoutFrom(Settings settings) {
    return Duration.ofSeconds(settings.getAsLong(
      ATTRIBUTE_REQUEST_TIMEOUT,
      UnboundidLdapClient.Builder.DEFAULT_LDAP_REQUEST_TIMEOUT.getSeconds()
    ));
  }

  private static Duration cacheTtlFrom(Settings settings) {
    return Duration.ofSeconds(settings.getAsLong(
      ATTRIBUTE_CACHE_TTL,
      UnboundidLdapClient.Builder.DEFAULT_LDAP_CACHE_TTL.getSeconds()
    ));
  }

  public String getName() {
    return name;
  }

  public LdapClient getClient() {
    return client;
  }
}
