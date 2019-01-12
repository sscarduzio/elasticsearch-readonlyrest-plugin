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

package tech.beshu.ror.unit.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.unit.acl.blocks.rules.AsyncAuthorization;
import tech.beshu.ror.unit.acl.definitions.ldaps.GroupsProviderLdapClient;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapClientFactory;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapGroup;
import tech.beshu.ror.commons.domain.__old_LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthorizationAsyncRule extends AsyncAuthorization {

  private final GroupsProviderLdapClient client;
  private final LdapAuthorizationRuleSettings settings;

  public LdapAuthorizationAsyncRule(LdapAuthorizationRuleSettings settings, LdapClientFactory factory, ESContext context) {
    super(context);
    this.client = factory.getClient(settings.getLdapSettings());
    this.client.addAvailableGroups(settings.getGroups());
    this.settings = settings;
  }

  @Override
  protected CompletableFuture<Boolean> authorize(__old_LoggedUser user) {

    // Fail early if we have they are looking for a current group that is not within the allowed ones
    if (user.getCurrentGroup().isPresent()) {
      String currGroup = user.getCurrentGroup().get();
      if (!settings.getGroups().contains(currGroup)) {
        return CompletableFuture.completedFuture(false);
      }
    }

    CompletableFuture<Set<LdapGroup>> accessibleGroups = client
        .userById(user.getId())
        .thenCompose(ldapUser -> ldapUser
            .map(client::userGroups)
            .orElseGet(() -> CompletableFuture.completedFuture(Sets.newHashSet()))
        );

    return accessibleGroups.thenApply(userLdapGroups -> {
      Set<String> availableGroupsForUser = checkWhatConfiguredGroupsUserHasAccess(userLdapGroups);

      // After collecting all groups info, detect if they requested a preferred group that is not available
      Optional<String> currentGroup = user.getCurrentGroup();
      if (currentGroup.isPresent() && !availableGroupsForUser.contains(currentGroup.get())) {
        return false;
      }

      // Add available group metadata
      user.addAvailableGroups(availableGroupsForUser);

      Optional<LdapGroup> firstGroup = userLdapGroups
          .stream()
          .filter(ulg -> settings.getGroups().contains(ulg.getName()))
          .findFirst();

      Boolean success = firstGroup.isPresent();

      if (success && !user.getCurrentGroup().isPresent()) {
        user.setCurrentGroup(firstGroup.get().getName());
      }

      return success;

    });

  }

  private Set<String> checkWhatConfiguredGroupsUserHasAccess(Set<LdapGroup> userLdapGroups) {
    if (userLdapGroups.isEmpty()) {
      return Collections.emptySet();
    }

    Set<String> userLdapGroupNames = userLdapGroups
        .stream()
        .map(LdapGroup::getName)
        .collect(Collectors.toSet());

    // Retain only groups that the client knows and the user has
    Set<String> remaining = settings.getLdapSettings().getAvailableGroups();
    remaining.addAll(client.getAvailableGroups());
    remaining.retainAll(userLdapGroupNames);

    return remaining;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  public LdapAuthorizationRuleSettings getSettings() {
    return settings;
  }
}
