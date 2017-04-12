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
package org.elasticsearch.plugin.readonlyrest.ldap.caching;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ldap.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.optionalAttributeValue;

public class GroupsProviderLdapClientCacheDecorator implements GroupsProviderLdapClient {

  private static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";

  private final GroupsProviderLdapClient underlyingClient;
  private final Cache<String, Set<LdapGroup>> ldapUserGroupsCache;
  private final AuthenticationLdapClientCacheDecorator authenticationLdapClientCacheDecorator;

  public static GroupsProviderLdapClient wrapInCacheIfCacheIsEnabled(Settings settings, GroupsProviderLdapClient client) {
    return optionalAttributeValue(ATTRIBUTE_CACHE_TTL, settings, ConfigReaderHelper.toDuration())
        .map(ttl -> ttl.isZero()
            ? client
            : new GroupsProviderLdapClientCacheDecorator(client, ttl))
        .orElse(client);
  }

  public GroupsProviderLdapClientCacheDecorator(GroupsProviderLdapClient underlyingClient, Duration ttl) {
    this.underlyingClient = underlyingClient;
    this.ldapUserGroupsCache = CacheBuilder.newBuilder()
        .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
        .build();
    authenticationLdapClientCacheDecorator = new AuthenticationLdapClientCacheDecorator(underlyingClient, ttl);
  }

  @Override
  public CompletableFuture<Set<LdapGroup>> userGroups(LdapUser user) {
    Set<LdapGroup> ldapUserGroup = ldapUserGroupsCache.getIfPresent(user.getUid());
    if(ldapUserGroup == null) {
      return underlyingClient.userGroups(user)
          .thenApply(groups -> {
            ldapUserGroupsCache.put(user.getUid(), groups);
            return groups;
          });
    }
    return CompletableFuture.completedFuture(ldapUserGroup);
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> userById(String userId) {
    return authenticationLdapClientCacheDecorator.userById(userId);
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
    return authenticationLdapClientCacheDecorator.authenticate(credentials);
  }
}
