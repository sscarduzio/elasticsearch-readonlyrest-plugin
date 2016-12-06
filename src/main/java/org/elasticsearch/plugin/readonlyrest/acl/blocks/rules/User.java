/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import java.util.Arrays;
import java.util.List;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeyRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1Rule;

/**
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class User {

  private final String username;
  private AuthKeyRule authKeyRule;
  private final List<String> groups;

  public User(Settings userProperties) throws UserNotConfiguredException {
    this.username = userProperties.get("username");
    try {
      this.authKeyRule = new AuthKeyRule(userProperties);
    } catch (RuleNotConfiguredException e) {
      try {
        this.authKeyRule = new AuthKeySha1Rule(userProperties);
      } catch (RuleNotConfiguredException e2) {
        throw new UserNotConfiguredException();
      }
    }
    String[] pGroups = userProperties.getAsArray("groups");
    if (pGroups != null && pGroups.length > 0) {
      this.groups = Arrays.asList(pGroups);
    } else {
      throw new UserNotConfiguredException();
    }
  }

  public String getUsername() {
    return username;
  }

  public AuthKeyRule getAuthKeyRule() {
    return authKeyRule;
  }

  public List<String> getGroups() {
    return groups;
  }

}
