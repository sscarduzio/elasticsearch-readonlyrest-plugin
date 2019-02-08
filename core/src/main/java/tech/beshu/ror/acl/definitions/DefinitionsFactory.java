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

package tech.beshu.ror.acl.definitions;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.acl.blocks.rules.impl.JwtExternalValidationHttpClient;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClientFactory;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceHttpClient;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceClientFactory;
import tech.beshu.ror.acl.definitions.groupsproviders.GroupsProviderServiceHttpClient;
import tech.beshu.ror.acl.definitions.ldaps.AuthenticationLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.GroupsProviderLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.LdapClientFactory;
import tech.beshu.ror.acl.definitions.ldaps.caching.AuthenticationLdapClientCacheDecorator;
import tech.beshu.ror.acl.definitions.ldaps.logging.AuthenticationLdapClientLoggingDecorator;
import tech.beshu.ror.acl.definitions.ldaps.logging.GroupsProviderLdapClientLoggingDecorator;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.ConnectionConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.SearchingUserConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UnboundidAuthenticationLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UnboundidGroupsProviderLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UserGroupsSearchFilterConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UserSearchFilterConfig;
import tech.beshu.ror.acl.definitions.users.User;
import tech.beshu.ror.acl.definitions.users.UserFactory;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.httpclient.ApacheHttpCoreClient;
import tech.beshu.ror.settings.definitions.AuthenticationLdapSettings;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettings;
import tech.beshu.ror.settings.definitions.GroupsProviderLdapSettings;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettings;
import tech.beshu.ror.settings.definitions.UserSettings;
import tech.beshu.ror.settings.rules.CacheSettings;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;
import tech.beshu.ror.settings.rules.NamedSettings;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Supplier;

import static tech.beshu.ror.acl.definitions.externalauthenticationservices.CachedExternalAuthenticationServiceClient.wrapInCacheIfCacheIsEnabled;
import static tech.beshu.ror.acl.definitions.groupsproviders.CachedGroupsProviderServiceClient.wrapInCacheIfCacheIsEnabled;
import static tech.beshu.ror.acl.definitions.ldaps.caching.GroupsProviderLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled;

public class DefinitionsFactory implements LdapClientFactory,
    ExternalAuthenticationServiceClientFactory,
    GroupsProviderServiceClientFactory,
    UserFactory {

  //private final HttpClient httpClient;
  private final ESContext context;
  private final Cache<String, GroupsProviderLdapClient> groupsProviderLdapClientsCache;
  private final Cache<String, AuthenticationLdapClient> authenticationLdapClientsCache;
  private final Cache<String, ExternalAuthenticationServiceClient> externalAuthenticationServiceClientsCache;
  private final Cache<String, GroupsProviderServiceClient> groupsProviderServiceClientsCache;
  private final ACL acl;
  private final Cache<String, ExternalAuthenticationServiceClient> jwtExternalValidatorClientCache;

  public DefinitionsFactory(ESContext context, ACL acl) {
    this.acl = acl;
    this.context = context;
    this.groupsProviderLdapClientsCache = CacheBuilder.newBuilder().build();
    this.authenticationLdapClientsCache = CacheBuilder.newBuilder().build();
    this.externalAuthenticationServiceClientsCache = CacheBuilder.newBuilder().build();
    this.groupsProviderServiceClientsCache = CacheBuilder.newBuilder().build();
    this.jwtExternalValidatorClientCache = CacheBuilder.newBuilder().build();
  }

  @Override
  public GroupsProviderLdapClient getClient(GroupsProviderLdapSettings settings) {
    return getOrCreate(
        settings,
        groupsProviderLdapClientsCache,
        () -> GroupsProviderLdapClientLoggingDecorator.wrapInLoggingIfIsLoggingEnabled(
            settings.getName(),
            context,
            wrapInCacheIfCacheIsEnabled(
                settings,
                new UnboundidGroupsProviderLdapClient(
                    new ConnectionConfig(
                        settings.getHost(),
                        settings.getServers(),
                        settings.getPort(),
                        settings.getConnectionPoolSize(),
                        settings.getConnectionTimeout(),
                        settings.getRequestTimeout(),
                        settings.isSslEnabled(),
                        settings.isTrustAllCertificates(),
                        settings.getHa()
                    ),
                    new UserSearchFilterConfig(
                        settings.getSearchUserBaseDn(),
                        settings.getUserIdAttribute()
                    ),
                    new UserGroupsSearchFilterConfig(
                        settings.getSearchGroupBaseDn(),
                        settings.getUniqueMemberAttribute(),
                        settings.getGroupSearchFilter(),
                        settings.getGroupNameAttribute(),
                        settings.isGroupsFromUser(),
                        settings.getGroupsFromUserAttribute()
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
        () -> AuthenticationLdapClientLoggingDecorator.wrapInLoggingIfIsLoggingEnabled(
            settings.getName(),
            context,
            AuthenticationLdapClientCacheDecorator.wrapInCacheIfCacheIsEnabled(
                settings,
                new UnboundidAuthenticationLdapClient(
                    new ConnectionConfig(
                        settings.getHost(),
                        settings.getServers(),
                        settings.getPort(),
                        settings.getConnectionPoolSize(),
                        settings.getConnectionTimeout(),
                        settings.getRequestTimeout(),
                        settings.isSslEnabled(),
                        settings.isTrustAllCertificates(),
                        settings.getHa()
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
                new ApacheHttpCoreClient(context, settings.getHttpConnectionSettings()),
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
                new ApacheHttpCoreClient(context, settings.getHttpConnectionSettings()),
                settings.getEndpoint(),
                settings.getAuthTokenName(),
                settings.getMethod(),
                settings.getAuthTokenPassedMethod(),
                settings.getResponseGroupsJsonPath(),
                settings.getDefaultHeaders(),
                settings.getDefaultQueryParameters(),
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
    if (cached != null)
      return cached;

    T created = creator.get();
    cache.put(settings.getName(), created);
    return created;
  }

  public ExternalAuthenticationServiceClient getClient(JwtAuthRuleSettings settings) {
    URI externalUrl = null;
    try {
      externalUrl = new URI(settings.getExternalValidator().get());
    } catch (URISyntaxException e) {
      e.printStackTrace();
    }
    URI finalExternalUrl = externalUrl;
    NamedSettings ns = settings;
    ExternalAuthenticationServiceClient authcli = new JwtExternalValidationHttpClient(
        new ApacheHttpCoreClient(context, settings.getExternalValidatorHttpConnectionSettings()),
        finalExternalUrl,
        settings.getExternalValidatorSuccessStatusCode()
    );
    return getOrCreate(ns, jwtExternalValidatorClientCache, () -> wrapInCacheIfCacheIsEnabled((CacheSettings) ns, authcli));

  }
}
