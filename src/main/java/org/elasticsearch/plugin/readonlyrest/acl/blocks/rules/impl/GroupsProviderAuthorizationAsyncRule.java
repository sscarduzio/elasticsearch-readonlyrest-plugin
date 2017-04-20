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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeArrayValue;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class GroupsProviderAuthorizationAsyncRule extends AsyncAuthorization {

  private static final String RULE_NAME = "groups_provider_authorization";
  private static final String ATTRIBUTE_USER_GROUPS_PROVIDER = "user_groups_provider";
  private static final String ATTRIBUTE_GROUPS = "groups";

  private final ProviderGroupsAuthDefinition providerGroupsAuthDefinition;

  private GroupsProviderAuthorizationAsyncRule(ProviderGroupsAuthDefinition definition) {
    this.providerGroupsAuthDefinition = definition;
  }

  public static Optional<GroupsProviderAuthorizationAsyncRule> fromSettings(Settings s,
      List<UserGroupProviderConfig> groupProviderConfigs)
      throws ConfigMalformedException {

    Map<String, Settings> groupBaseAuthElements = s.getGroups(RULE_NAME);
    if (groupBaseAuthElements.isEmpty()) return Optional.empty();
    if (groupBaseAuthElements.size() != 1) {
      throw new ConfigMalformedException("Malformed rule" + RULE_NAME);
    }
    Settings groupBaseAuthSettings = Lists.newArrayList(groupBaseAuthElements.values()).get(0);

    Map<String, UserGroupProviderConfig> userGroupProviderConfigByName =
        groupProviderConfigs.stream().collect(Collectors.toMap(UserGroupProviderConfig::getName, Function.identity()));

    String name = requiredAttributeValue(ATTRIBUTE_USER_GROUPS_PROVIDER, groupBaseAuthSettings);
    if (!userGroupProviderConfigByName.containsKey(name)) {
      throw new ConfigMalformedException("User groups provider with name [" + name + "] wasn't defined.");
    }
    List<String> groups = requiredAttributeArrayValue(ATTRIBUTE_GROUPS, groupBaseAuthSettings);

    return Optional.of(new GroupsProviderAuthorizationAsyncRule(
        new ProviderGroupsAuthDefinition(userGroupProviderConfigByName.get(name), groups)
    ));
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    return providerGroupsAuthDefinition.config.getClient()
        .fetchGroupsFor(user)
        .thenApply(this::checkUserGroups);
  }

  private boolean checkUserGroups(Set<String> groups ) {

          Sets.SetView<String> intersection = Sets.intersection(providerGroupsAuthDefinition.groups, Sets.newHashSet(groups));
          return !intersection.isEmpty();

  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

  private static class ProviderGroupsAuthDefinition {
    private final UserGroupProviderConfig config;
    private final ImmutableSet<String> groups;

    ProviderGroupsAuthDefinition(UserGroupProviderConfig config, List<String> groups) {
      this.config = config;
      this.groups = ImmutableSet.copyOf(groups);
    }
  }

}
