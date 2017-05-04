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

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsSyncRule extends SyncRule {

  private final List<User> users;
  private final List<String> groups;

  private GroupsSyncRule(Settings s, List<User> userList) throws RuleNotConfiguredException {
    super();
    users = userList;
    List<String> pGroups = Lists.newArrayList(s.getAsArray(this.getKey()));
    if(pGroups.isEmpty()) throw new RuleNotConfiguredException();
    groups = pGroups;
  }

  public static Optional<GroupsSyncRule> fromSettings(Settings s, List<User> userList) {
    try {
      return Optional.of(new GroupsSyncRule(s, userList));
    } catch (RuleNotConfiguredException ignored) {
      return Optional.empty();
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    for (User user : this.users) {
      if (user.getAuthKeyRule().match(rc).isMatch()) {
        List<String> commonGroups = new ArrayList<>(user.getGroups());
        commonGroups.retainAll(this.groups);
        if (!commonGroups.isEmpty()) {
          return MATCH;
        }
      }
    }
    return NO_MATCH;
  }

}
