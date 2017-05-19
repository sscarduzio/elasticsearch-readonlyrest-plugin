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

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.users.UserFactory;
import org.elasticsearch.plugin.readonlyrest.settings.rules.GroupsRuleSettings;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsSyncRule extends SyncRule {

  private final GroupsRuleSettings settings;
  private final UserFactory userFactory;

  public GroupsSyncRule(GroupsRuleSettings s, UserFactory userFactory) {
    this.settings = s;
    this.userFactory = userFactory;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Set<String> resolvedGroups = settings.getGroups().stream()
        .map(g -> g.getValue(rc))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toSet());
    boolean anyMatch = settings.getUsersSettings().stream()
        .map(userFactory::getUser)
        .anyMatch(user -> Sets.intersection(resolvedGroups, user.getGroups()).isEmpty()
            ? false
            : user.getAuthKeyRule().match(rc).isMatch()
        );

    return anyMatch ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}
