package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.time.Duration;
import java.util.List;

public class GroupsProviderAuthorizationRuleSettings extends Settings {

  @JsonProperty("user_groups_provider")
  private String groupsProviderName;

  @JsonProperty("groups")
  private List<String> groups = Lists.newArrayList();

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

  private UserGroupsProviderSettings userGroupsProviderSettings;

  GroupsProviderAuthorizationRuleSettings() {}

  public GroupsProviderAuthorizationRuleSettings(String groupsProviderName) {
    this.groupsProviderName = groupsProviderName;
  }

  public String getGroupsProviderName() {
    return groupsProviderName;
  }

  public ImmutableList<String> getGroups() {
    return ImmutableList.copyOf(groups);
  }

  public Duration getCacheTtlInSec() {
    return Duration.ofSeconds(cacheTtlInSec);
  }

  public void setUserGroupsProviderSettings(UserGroupsProviderSettings userGroupsProviderSettings) {
    this.userGroupsProviderSettings = userGroupsProviderSettings;
  }

  @Override
  protected void validate() {
    if (groupsProviderName == null) {
      throw new ConfigMalformedException("'user_groups_provider' was not defined in groups_provider_authorization rule");
    }
    if (userGroupsProviderSettings == null) {
      throw new ConfigMalformedException("Cannot find Groups Provider configuration with name [" + groupsProviderName + "]");
    }
    if(groups.isEmpty()) {
      throw new ConfigMalformedException("No groups defined in 'user_groups_provider' rule [" + groupsProviderName + "]" );
    }
  }
}
