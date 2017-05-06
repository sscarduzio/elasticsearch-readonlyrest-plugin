package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;

import java.time.Duration;
import java.util.Set;

public class GroupsProviderAuthorizationRuleSettings implements RuleSettings, CacheSettings {

  public static final String ATTRIBUTE_NAME = "groups_provider_authorization";

  private static final String GROUPS_PROVIDER_NAME = "user_groups_provider";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Set<String> groups;
  private final Duration cacheTtl;
  private final UserGroupsProviderSettings userGroupsProviderSettings;

  @SuppressWarnings("unchecked")
  public static GroupsProviderAuthorizationRuleSettings from(RawSettings settings,
                                                             UserGroupsProviderSettingsCollection groupsProviderSettingsCollection) {
    String providerName = settings.stringReq(GROUPS_PROVIDER_NAME);
    Set<String> groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    return new GroupsProviderAuthorizationRuleSettings(
        groupsProviderSettingsCollection.get(providerName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  private GroupsProviderAuthorizationRuleSettings(UserGroupsProviderSettings settings, Set<String> groups, Duration cacheTtl) {
    this.groups = groups;
    this.cacheTtl = cacheTtl;
    this.userGroupsProviderSettings = settings;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public Set<String> getGroups() {
    return groups;
  }

  public UserGroupsProviderSettings getUserGroupsProviderSettings() {
    return userGroupsProviderSettings;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}
