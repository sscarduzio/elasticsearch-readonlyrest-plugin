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

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.acl.definitions.users.User;
import tech.beshu.ror.acl.definitions.users.UserFactory;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.GroupsRuleSettings;
import tech.beshu.ror.utils.FuturesSequencer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsAsyncRule extends AsyncRule implements Authorization, Authentication {

  public static final String CURRENT_GROUP_HEADER = "x-ror-current-group";
  private static final boolean ROR_KIBANA_METADATA_ENABLED =
    !"false".equalsIgnoreCase(System.getProperty("com.readonlyrest.kibana.metadata"));
  private static final String AVAILABLE_GROUPS_HEADER = "x-ror-available-groups";
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

    // All configured groups, contextualized
    Set<String> resolvedGroups = settings.getGroups().stream()
      .map(g -> g.getValue(rc))
      .filter(Optional::isPresent)
      .map(Optional::get)
      .collect(Collectors.toSet());

    return FuturesSequencer.runInSeqUntilConditionIsUndone(

      // Iterator
      settings.getUsersSettings().stream().filter(us -> !Sets.intersection(us.getGroups(), resolvedGroups).isEmpty()).iterator(),

      // Async map
      uSettings -> {
        return users.get(uSettings.getUsername()).getAuthKeyRule().match(rc)
          .exceptionally(e -> {
            e.printStackTrace();
            return NO_MATCH;
          });
      },

      // Boolean decision
      (uSettings, ruleExit) -> {

        Set<String> groupsOfCurrentBlock = Sets.intersection(resolvedGroups, uSettings.getGroups());

        if (ROR_KIBANA_METADATA_ENABLED) {
          // check preferred group from header.
          String preferredGroup = rc.getHeaders().get(CURRENT_GROUP_HEADER);
          if (!Strings.isNullOrEmpty(preferredGroup)) {
            try {
              Set<String> tmp = Sets.newHashSet();
              if (groupsOfCurrentBlock.contains(preferredGroup)) {
                groupsOfCurrentBlock = tmp;
                groupsOfCurrentBlock.add(preferredGroup);
                rc.setResponseHeader(CURRENT_GROUP_HEADER, preferredGroup);
              }
              else {
                groupsOfCurrentBlock = tmp;
              }
            } catch (Throwable t) {
              t.printStackTrace();
            }
          }

          // #TODO add groups (with indices and kibana access and kibana index) to RC.
          rc.setResponseHeader(AVAILABLE_GROUPS_HEADER, Joiner.on(",").join(uSettings.getGroups()));
        }

        return !groupsOfCurrentBlock.isEmpty() && ruleExit.isMatch();
      },

      // If never true..
      nothing -> NO_MATCH
    );
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
