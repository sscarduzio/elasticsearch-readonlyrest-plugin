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

package tech.beshu.ror.settings.rules;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettings;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

public class __old_GroupsProviderAuthorizationRuleSettings implements RuleSettings, CacheSettings {

  public static final String ATTRIBUTE_NAME = "groups_provider_authorization";

  private static final String GROUPS_PROVIDER_NAME = "user_groups_provider";
  private static final String GROUPS = "groups";
  private static final String USERS = "users";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Set<String> groups;
  private final Duration cacheTtl;
  private final UserGroupsProviderSettings userGroupsProviderSettings;
  private final Set<String> users;

  private __old_GroupsProviderAuthorizationRuleSettings(UserGroupsProviderSettings settings, Set<String> groups, Set<String> users, Duration cacheTtl) {
    this.groups = groups;
    this.users = users;
    this.cacheTtl = cacheTtl;
    this.userGroupsProviderSettings = settings;

    // Map merging
    Map<String, Set<String>> newUser2availGroups = Maps.newHashMap();
    Map<String, Set<String>> user2availGroups = userGroupsProviderSettings.getUser2availGroups();
    for (String user : users) {
      Set<String> groupsOfUser = user2availGroups.get(user);
      if (groupsOfUser != null) {
        groupsOfUser.addAll(groups);
      }
      else {
        groupsOfUser = Sets.newHashSet(groups);
      }
      newUser2availGroups.put(user, groupsOfUser);
    }
    user2availGroups.putAll(newUser2availGroups);
  }

  @SuppressWarnings("unchecked")
  public static __old_GroupsProviderAuthorizationRuleSettings from(RawSettings settings,
      UserGroupsProviderSettingsCollection groupsProviderSettingsCollection) {
    String providerName = settings.stringReq(GROUPS_PROVIDER_NAME);
    Set<String> groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    Set<String> users = (Set<String>) settings.notEmptySetOpt(USERS).orElse(Sets.newHashSet("*"));
    return new __old_GroupsProviderAuthorizationRuleSettings(
        groupsProviderSettingsCollection.get(providerName),
        groups,
        users,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  public Set<String> getUsers() {
    return users;
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
