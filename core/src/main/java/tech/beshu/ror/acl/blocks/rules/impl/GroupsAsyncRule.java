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

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.acl.definitions.users.User;
import tech.beshu.ror.acl.definitions.users.UserFactory;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.definitions.UserSettings;
import tech.beshu.ror.settings.rules.GroupsRuleSettings;
import tech.beshu.ror.utils.FuturesSequencer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsAsyncRule extends AsyncRule implements Authorization, Authentication {

  private final GroupsRuleSettings settings;
  private final Map<String, User> users;

  public GroupsAsyncRule(GroupsRuleSettings s, UserFactory userFactory) {
    this.settings = s;
    this.users = settings.getUsersSettings().stream()
                         .map(uSettings -> userFactory.getUser(uSettings))
                         .collect(Collectors.toMap(User::getUsername, Function.identity()));
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {

    // All configured groups in a block's group rule, contextualized
    Set<String> resolvedGroups = settings.getGroups().stream()
                                         .map(g -> g.getValue(rc))
                                         .filter(Optional::isPresent)
                                         .map(Optional::get)
                                         .collect(Collectors.toSet());

    List<UserSettings> userSettingsToCheck = settings.getUsersSettings();

    // Filter the userSettings to leave only the ones which include the current group
    // #TODO move to proper use of optional
    final String preferredGroup = rc.getLoggedInUser().isPresent() ?
        rc.getLoggedInUser().get().resolveCurrentGroup(rc.getHeaders()).orElse(null) : new LoggedUser("x").resolveCurrentGroup(rc.getHeaders()).orElse(null);

    // Exclude userSettings that don't contain groups in this groupRule
    userSettingsToCheck = userSettingsToCheck.stream()
                                             .filter(us -> !Sets.intersection(us.getGroups(), resolvedGroups).isEmpty())
                                             .collect(Collectors.toList());

    if (!Strings.isNullOrEmpty(preferredGroup)) {
      if (!resolvedGroups.contains(preferredGroup)) {
        return CompletableFuture.completedFuture(NO_MATCH);
      }
    }

    if (userSettingsToCheck.isEmpty()) {
      return CompletableFuture.completedFuture(NO_MATCH);
    }

    // Check remaining userSettings for matching auth
    return FuturesSequencer.runInSeqUntilConditionIsUndone(

        // Iterator of users which match at least one group in this block's group rule
        userSettingsToCheck.iterator(),

        // Asynchronously map for each userSetting, return MATCH when we authenticated the first user
        uSettings -> {
          if (!Strings.isNullOrEmpty(preferredGroup) && !uSettings.getGroups().contains(preferredGroup)) {
            return CompletableFuture.completedFuture(NO_MATCH);
          }
          return users.get(uSettings.getUsername())
                      .getAuthKeyRule()
                      .match(rc)
                      .exceptionally(e -> {
                        e.printStackTrace();
                        return NO_MATCH;
                      }).thenApply(rExitRes -> {
                if (!rExitRes.isMatch()) {
                  return rExitRes;
                }
                if (!rc.getLoggedInUser().filter(lu -> uSettings.getUsername().equals(lu.getId())).isPresent()) {
                  // User is authenticated, but was not declared as "username"
                  return NO_MATCH;
                }
                return rExitRes;
              });
        },

        // Boolean decision (true = break loop)
        (uSettings, ruleExit) -> {

          if (!ruleExit.isMatch() && rc.getLoggedInUser().isPresent()) {
            return false;
          }

          // Now that we have a user, and we know this is a match, we can populate the headers.
          if (ruleExit.isMatch()) {
            rc.getLoggedInUser().ifPresent(lu -> {
              User u = users.get(lu.getId());
              if (u != null) {
                lu.addAvailableGroups(u.getGroups());
                Optional<String> cu = lu.resolveCurrentGroup(rc.getHeaders());
                if (!cu.isPresent() && ruleExit.isMatch()) {
                  lu.setCurrentGroup(resolvedGroups.iterator().next());
                }
              }
            });
          }
          return ruleExit.isMatch();
        },

        // If never true..
        nothing -> NO_MATCH
    ).exceptionally(e -> {
      e.printStackTrace();
      throw new CompletionException(e);
    });

  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
