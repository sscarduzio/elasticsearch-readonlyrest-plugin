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
package org.elasticsearch.plugin.readonlyrest.acl.definitions;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.ACL;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.ConnectionConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.SearchingUserConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UnboundidAuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UnboundidGroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserGroupsSearchFilterConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserSearchFilterConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.users.User;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.users.UserFactory;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClient;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.AuthenticationLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.NamedSettings;

import java.util.function.Supplier;

import static org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.CachedExternalAuthenticationServiceClient.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.CachedGroupsProviderServiceClient.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.caching.AuthenticationLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.caching.GroupsProviderLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.logging.AuthenticationLdapClientLoggingDecorator.wrapInLoggingIfIsLoggingEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.logging.GroupsProviderLdapClientLoggingDecorator.wrapInLoggingIfIsLoggingEnabled;

public class DefinitionsFactory implements LdapClientFactory,
  ExternalAuthenticationServiceClientFactory,
  GroupsProviderServiceClientFactory,
  UserFactory {

  private final HttpClient httpClient;
  private final ESContext context;
  private final Cache<String, GroupsProviderLdapClient> groupsProviderLdapClientsCache;
  private final Cache<String, AuthenticationLdapClient> authenticationLdapClientsCache;
  private final Cache<String, ExternalAuthenticationServiceClient> externalAuthenticationServiceClientsCache;
  private final Cache<String, GroupsProviderServiceClient> groupsProviderServiceClientsCache;
  private final ACL acl;

  public DefinitionsFactory(ESContext context, ACL acl) {
    this.acl = acl;
    this.httpClient = context.mkHttpClient();
    this.context = context;
    this.groupsProviderLdapClientsCache = CacheBuilder.newBuilder().build();
    this.authenticationLdapClientsCache = CacheBuilder.newBuilder().build();
    this.externalAuthenticationServiceClientsCache = CacheBuilder.newBuilder().build();
    this.groupsProviderServiceClientsCache = CacheBuilder.newBuilder().build();
  }

  @Override
  public GroupsProviderLdapClient getClient(GroupsProviderLdapSettings settings) {
    return getOrCreate(
      settings,
      groupsProviderLdapClientsCache,
      () -> wrapInLoggingIfIsLoggingEnabled(
        settings.getName(),
        context,
        wrapInCacheIfCacheIsEnabled(
          settings,
          new UnboundidGroupsProviderLdapClient(
            new ConnectionConfig(
              settings.getHost(),
              settings.getPort(),
              settings.getConnectionPoolSize(),
              settings.getConnectionTimeout(),
              settings.getRequestTimeout(),
              settings.isSslEnabled(),
              settings.isTrustAllCertificates()
            ),
            new UserSearchFilterConfig(
              settings.getSearchUserBaseDn(),
              settings.getUserIdAttribute()
            ),
            new UserGroupsSearchFilterConfig(
              settings.getSearchGroupBaseDn(),
              settings.getUniqueMemberAttribute()
            ),
            settings.getSearchingUserSettings().map(s ->
                                                      new SearchingUserConfig(s.getDn(), s.getPassword())
            ),
            context
          )
        )
      )
    );
  }

  @Override
  public AuthenticationLdapClient getClient(AuthenticationLdapSettings settings) {
    return getOrCreate(
      settings,
      authenticationLdapClientsCache,
      () -> wrapInLoggingIfIsLoggingEnabled(
        settings.getName(),
        context,
        wrapInCacheIfCacheIsEnabled(
          settings,
          new UnboundidAuthenticationLdapClient(
            new ConnectionConfig(
              settings.getHost(),
              settings.getPort(),
              settings.getConnectionPoolSize(),
              settings.getConnectionTimeout(),
              settings.getRequestTimeout(),
              settings.isSslEnabled(),
              settings.isTrustAllCertificates()
            ),
            new UserSearchFilterConfig(
              settings.getSearchUserBaseDn(),
              settings.getUserIdAttribute()
            ),
            settings.getSearchingUserSettings().map(s ->
                                                      new SearchingUserConfig(s.getDn(), s.getPassword())
            ),
            context
          )
        )
      )
    );
  }

  @Override
  public ExternalAuthenticationServiceClient getClient(ExternalAuthenticationServiceSettings settings) {
    return getOrCreate(
      settings,
      externalAuthenticationServiceClientsCache,
      () -> wrapInCacheIfCacheIsEnabled(
        settings,
        new ExternalAuthenticationServiceHttpClient(
          httpClient,
          settings.getEndpoint(),
          settings.getSuccessStatusCode()
        )
      )
    );
  }

  @Override
  public GroupsProviderServiceClient getClient(UserGroupsProviderSettings settings) {
    return getOrCreate(
      settings,
      groupsProviderServiceClientsCache,
      () -> wrapInCacheIfCacheIsEnabled(
        settings,
        new GroupsProviderServiceHttpClient(
          settings.getName(),
          httpClient,
          settings.getEndpoint(),
          settings.getAuthTokenName(),
          settings.getAuthTokenPassedMethod(),
          settings.getResponseGroupsJsonPath(),
          context
        )
      )
    );
  }

  @Override
  public User getUser(UserSettings settings) {
    return new User(
      settings.getUsername(),
      settings.getGroups(),
      acl.getUserRuleFactory().create(settings.getAuthKeyProviderSettings())
    );
  }

  private <T> T getOrCreate(NamedSettings settings, Cache<String, T> cache, Supplier<T> creator) {
    T cached = cache.getIfPresent(settings.getName());
    if (cached != null) return cached;

    T created = creator.get();
    cache.put(settings.getName(), created);
    return created;
  }
}
