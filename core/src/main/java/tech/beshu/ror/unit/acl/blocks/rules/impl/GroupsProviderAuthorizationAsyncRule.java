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
import tech.beshu.ror.unit.acl.definitions.groupsproviders.GroupsProviderServiceClient;
import tech.beshu.ror.unit.acl.definitions.groupsproviders.GroupsProviderServiceClientFactory;
import tech.beshu.ror.commons.domain.__old_LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class GroupsProviderAuthorizationAsyncRule extends AsyncAuthorization {

  private final GroupsProviderAuthorizationRuleSettings settings;
  private final GroupsProviderServiceClient client;
  private final MatcherWithWildcards usersMatcher;

  public GroupsProviderAuthorizationAsyncRule(GroupsProviderAuthorizationRuleSettings settings,
      GroupsProviderServiceClientFactory factory,
      ESContext context) {
    super(context);
    this.settings = settings;
    this.client = factory.getClient(settings.getUserGroupsProviderSettings());
    this.usersMatcher = new MatcherWithWildcards(settings.getUsers());
  }

  @Override
  protected CompletableFuture<Boolean> authorize(__old_LoggedUser user) {
    if (!usersMatcher.match(user.getId())) {
      return CompletableFuture.completedFuture(false);
    }
    return client
        .fetchGroupsFor(user)
        // No wildcard matching for configured groups, but users can be declared as wildcards.
        .thenApply(fetchedGroupsForUser -> {

          // Exit early if resolved groups have nothing to do with the ones configured in this rule
          Sets.SetView<String> intersection = Sets.intersection(settings.getGroups(), Sets.newHashSet(fetchedGroupsForUser));
          if (intersection.isEmpty()) {
            return false;
          }

          System.out.println("user: " + user.getId() + " has groups: " + fetchedGroupsForUser + ", intersected: " + intersection);

          // Exit early if the request has a current group that does not belong to this rule, or is not resolved for user
          if (user.getCurrentGroup().isPresent()) {
            String currGroup = user.getCurrentGroup().get();
            System.out.println("found current group: " + currGroup);
            if (!intersection.contains(currGroup)) {
              System.out.println("current group in header does not match any available groups in rule " + currGroup);
              return false;
            }
          }

          // Set current group as the first of the list, if was absent (this will surface on the response header)
          else {
            String curGroup = intersection.immutableCopy().iterator().next();
            System.out.println("setting current group: " + curGroup);
            user.setCurrentGroup(curGroup);
          }

          // Exploring all the __old_ACL for available groups for this user, known what their resolved groups are, and what the __old_ACL has to offer for the user
          Set<String> matchingUserPatterns = new MatcherWithWildcards(
              settings.getUserGroupsProviderSettings()
                      .getUser2availGroups().keySet())
              .matchingMatchers(Sets.newHashSet(user.getId()));
          Set<String> availGroupsForUser = Sets.newHashSet();
          for (String up : matchingUserPatterns) {
            availGroupsForUser.addAll(settings.getUserGroupsProviderSettings().getUser2availGroups().get(up));
          }
          availGroupsForUser = Sets.intersection(availGroupsForUser, fetchedGroupsForUser);
          System.out.println("adding available groups for user " + user.getId() + ": " + availGroupsForUser);

          user.addAvailableGroups(availGroupsForUser);

          return true;
        });
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}
