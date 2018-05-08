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

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.AsyncAuthorization;
import tech.beshu.ror.acl.definitions.ldaps.GroupsProviderLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.LdapClientFactory;
import tech.beshu.ror.acl.definitions.ldaps.LdapGroup;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;

import java.util.Collections;
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
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    CompletableFuture<Set<LdapGroup>> accessibleGroups = client
        .userById(user.getId())
        .thenCompose(ldapUser -> ldapUser
            .map(client::userGroups)
            .orElseGet(() -> CompletableFuture.completedFuture(Sets.newHashSet()))
        );

    return accessibleGroups.thenApply(userLdapGroups -> {
      Set<String> availableGroupsForUser = checkWhatConfiguredGroupsUserHasAccess(userLdapGroups);

      // Add available group metadata
      user.addAvailableGroups(availableGroupsForUser);

      return userLdapGroups
          .stream()
          .filter(ulg -> settings.getGroups().contains(ulg.getName()))
          .findFirst()
          .isPresent();

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
    Set<String> remaining = client.getAvailableGroups();
    remaining.retainAll(userLdapGroupNames);

    return remaining;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}
