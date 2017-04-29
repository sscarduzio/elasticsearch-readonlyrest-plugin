package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;

import java.time.Duration;
import java.util.List;

public class GroupsProviderAuthorizationRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "groups_provider_authorization";

  private static final String GROUPS_PROVIDER_NAME = "user_groups_provider";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final List<String> groups;
  private final Duration cacheTtl;
  private final UserGroupsProviderSettings userGroupsProviderSettings;

  @SuppressWarnings("unchecked")
  public static GroupsProviderAuthorizationRuleSettings from(RawSettings settings,
                                                             UserGroupsProviderSettingsCollection groupsProviderSettingsCollection) {
    String providerName = settings.stringReq(GROUPS_PROVIDER_NAME);
    List<String> groups = (List<String>) settings.notEmptyListReq(GROUPS);
    return new GroupsProviderAuthorizationRuleSettings(
        groupsProviderSettingsCollection.get(providerName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  private GroupsProviderAuthorizationRuleSettings(UserGroupsProviderSettings settings, List<String> groups, Duration cacheTtl) {
    this.groups = groups;
    this.cacheTtl = cacheTtl;
    this.userGroupsProviderSettings = settings;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public List<String> getGroups() {
    return groups;
  }

  public UserGroupsProviderSettings getUserGroupsProviderSettings() {
    return userGroupsProviderSettings;
  }
}
