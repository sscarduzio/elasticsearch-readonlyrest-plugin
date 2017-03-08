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
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRule;

import java.util.List;
import java.util.Map;

/**
 * Created by ah on 15/02/2016.
 */
public class ProxyAuthSyncRule extends SyncRule implements UserRule {

  private static final String HEADER = "X-Forwarded-User";
  private List<String> userList;

  public ProxyAuthSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    String[] users = s.getAsArray(getKey());
    if (users != null && users.length > 0) {
      userList = Lists.newArrayList();
      for (int i = 0; i < users.length; i++) {
        if (!Strings.isNullOrEmpty(users[i])) {
          userList.add(users[i].trim());
        }
      }
    }
    else {
      throw new RuleNotConfiguredException();
    }
  }

  public static String getUser(Map<String, String> headers) {
    String h = headers.get(HEADER);
    if (h == null || h.trim().length() == 0)
      return null;
    return h;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    String h = getUser(rc.getHeaders());

    if (h == null) {
      return NO_MATCH;
    }

    if (h.length() == 0) {
      return NO_MATCH;
    }

    for (String user : userList) {
      if ("*".equals(user)) {
        return MATCH;
      }
      if (user.equals(h)) {
        return MATCH;
      }
    }

    return NO_MATCH;
  }

}
