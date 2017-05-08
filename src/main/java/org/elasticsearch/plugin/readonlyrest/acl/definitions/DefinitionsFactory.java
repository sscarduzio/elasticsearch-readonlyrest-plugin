package org.elasticsearch.plugin.readonlyrest.acl.definitions;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
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

public class DefinitionsFactory implements LdapClientFactory,
    ExternalAuthenticationServiceClientFactory,
    GroupsProviderServiceClientFactory,
    UserFactory {

  private final UserRuleFactory userRuleFactory;
  private final ESContext context;
  private final Cache<String, GroupsProviderLdapClient> groupsProviderLdapClientsCache;
  private final Cache<String, AuthenticationLdapClient> authenticationLdapClientsCache;
  private final Cache<String, ExternalAuthenticationServiceClient> externalAuthenticationServiceClientsCache;
  private final Cache<String, GroupsProviderServiceClient> groupsProviderServiceClientsCache;

  public DefinitionsFactory(UserRuleFactory userRuleFactory, ESContext context) {
    this.userRuleFactory = userRuleFactory;
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
        () -> wrapInCacheIfCacheIsEnabled(
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
    );
  }

  @Override
  public AuthenticationLdapClient getClient(AuthenticationLdapSettings settings) {
    return getOrCreate(
        settings,
        authenticationLdapClientsCache,
        () -> wrapInCacheIfCacheIsEnabled(
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
        userRuleFactory.create(settings.getAuthKeyProviderSettings())
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
