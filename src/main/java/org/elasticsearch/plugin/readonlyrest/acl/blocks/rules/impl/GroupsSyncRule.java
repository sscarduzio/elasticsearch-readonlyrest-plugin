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

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsSyncRule extends SyncRule {

  private final List<User> users;
  private final List<String> groups;

  public GroupsSyncRule(Settings s, List<User> userList) throws RuleNotConfiguredException {
    super();
    users = userList;
    String[] pGroups = s.getAsArray(this.getKey());
    if (pGroups != null && pGroups.length > 0) {
      this.groups = Arrays.asList(pGroups);
    }
    else {
      throw new RuleNotConfiguredException();
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
