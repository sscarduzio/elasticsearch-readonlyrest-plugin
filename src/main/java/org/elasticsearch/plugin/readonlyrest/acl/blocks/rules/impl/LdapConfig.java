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
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.BaseLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.ConnectionConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.SearchingUserConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UnboundidAuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UnboundidGroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserGroupsSearchFilterConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserSearchFilterConfig;

import java.time.Duration;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.caching.AuthenticationLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.caching.GroupsProviderLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.logging.AuthenticationLdapClientLoggingDecorator.wrapInLoggingIfIsLoggingEnabled;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.toBoolen;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.toDuration;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.toInteger;

public class LdapConfig<T extends BaseLdapClient> {

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
  private static String ATTRIBUTE_SSL_ENABLED = "ssl_enabled";
  private static String ATTRIBUTE_SSL_TRUST_ALL_CERTS = "ssl_trust_all_certs";

  private final String name;
  private final T client;

  private LdapConfig(String name, T client) {
    this.name = name;
    this.client = client;
  }

  public static LdapConfig<?> fromSettings(Settings settings, ESContext context) throws ConfigMalformedException {
    String name = nameFrom(settings);
    ConnectionConfig connectionConfig = connectionConfigFrom(name, settings);
    UserSearchFilterConfig userSearchFilterConfig = userSearchFilterConfigFrom(name, settings);
    Optional<UserGroupsSearchFilterConfig> userGroupsSearchFilterConfigOpt = userGroupsSearchFilterConfigFrom(settings);
    Optional<SearchingUserConfig> searchingUserConfig = searchingUserConfigFrom(settings);

    return userGroupsSearchFilterConfigOpt.<LdapConfig<?>>map(userGroupsSearchFilterConfig ->
        new LdapConfig<>(name, wrapInLoggingIfIsLoggingEnabled(
            name,
            context,
            wrapInCacheIfCacheIsEnabled(
                settings,
                new UnboundidGroupsProviderLdapClient(
                    connectionConfig,
                    userSearchFilterConfig,
                    userGroupsSearchFilterConfig,
                    searchingUserConfig)
            )
        ))
    ).orElseGet(() ->
        new LdapConfig<>(name, wrapInLoggingIfIsLoggingEnabled(
            name,
            context,
            wrapInCacheIfCacheIsEnabled(
                settings,
                new UnboundidAuthenticationLdapClient(
                    connectionConfig,
                    userSearchFilterConfig,
                    searchingUserConfig)
            )
        )));
  }

  private static ConnectionConfig connectionConfigFrom(String name, Settings settings) {
    ConnectionConfig.Builder builder = new ConnectionConfig.Builder(hostFrom(settings, name));
    portFrom(settings).map(builder::setPort);
    sslEnabledFrom(settings).map(builder::setSslEnabled);
    trustAllCertsFrom(settings).map(builder::setTrustAllCerts);
    poolSizeFrom(settings).map(builder::setPoolSize);
    connectionTimeoutFrom(settings).map(builder::setConnectionTimeout);
    requestTimeoutFrom(settings).map(builder::setRequestTimeout);
    return builder.build();
  }

  private static UserSearchFilterConfig userSearchFilterConfigFrom(String name, Settings settings) {
    UserSearchFilterConfig.Builder builder = new UserSearchFilterConfig.Builder(searchUserBaseDnFrom(settings, name));
    uidAttributeFrom(settings).map(builder::setUidAttribute);
    return builder.build();
  }

  private static Optional<UserGroupsSearchFilterConfig> userGroupsSearchFilterConfigFrom(Settings settings) {
    return searchGroupsBaseDnFrom(settings).map(g -> {
          UserGroupsSearchFilterConfig.Builder builder = new UserGroupsSearchFilterConfig.Builder(g);
          uniqueMemberAttributeFrom(settings).map(builder::setUniqueMemberAttribute);
          return builder.build();
        }
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

  private static Optional<Boolean> sslEnabledFrom(Settings settings) {
    return toBoolen(settings.get(ATTRIBUTE_SSL_ENABLED));
  }

  private static Optional<Boolean> trustAllCertsFrom(Settings settings) {
    return toBoolen(settings.get(ATTRIBUTE_SSL_TRUST_ALL_CERTS));
  }

  private static Optional<SearchingUserConfig> searchingUserConfigFrom(Settings settings) {
    Optional<String> bindDn = Optional.ofNullable(settings.get(ATTRIBUTE_BIND_DN));
    Optional<String> bindPassword = Optional.ofNullable(settings.get(ATTRIBUTE_BIND_PASSWORD));
    Optional<SearchingUserConfig> searchingUserConfig;
    if (bindDn.isPresent() && bindPassword.isPresent()) {
      searchingUserConfig = Optional.of(new SearchingUserConfig(bindDn.get(), bindPassword.get()));
    } else if (!bindDn.isPresent() && !bindPassword.isPresent()) {
      searchingUserConfig = Optional.empty();
    } else {
      throw new ConfigMalformedException("LDAP definition malformed - must configure both params [" +
          ATTRIBUTE_BIND_DN + ", " + ATTRIBUTE_BIND_PASSWORD + "]");
    }
    return searchingUserConfig;
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

  private static Optional<String> searchGroupsBaseDnFrom(Settings settings) {
    return Optional.ofNullable(settings.get(ATTRIBUTE_SEARCH_GROUPS_BASE_DN));
  }

  private static Optional<String> uidAttributeFrom(Settings settings) {
    return Optional.ofNullable(settings.get(ATTRIBUTE_UID_ATTRIBUTE));
  }

  private static Optional<String> uniqueMemberAttributeFrom(Settings settings) {
    return Optional.ofNullable(settings.get(ATTRIBUTE_UNIQUE_MEMEBER_ATTRIBUTE));
  }

  private static Optional<Integer> portFrom(Settings settings) {
    return toInteger(settings.get(ATTRIBUTE_PORT));
  }

  private static Optional<Integer> poolSizeFrom(Settings settings) {
    return toInteger(settings.get(ATTRIBUTE_CONNECTION_POOL_SIZE));
  }

  private static Optional<Duration> connectionTimeoutFrom(Settings settings) {
    return toDuration(settings.get(ATTRIBUTE_CONNECTION_TIMEOUT));
  }

  private static Optional<Duration> requestTimeoutFrom(Settings settings) {
    return toDuration(settings.get(ATTRIBUTE_REQUEST_TIMEOUT));
  }

  public String getName() {
    return name;
  }

  public T getClient() {
    return client;
  }

}
