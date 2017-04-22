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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;

import java.util.List;

/**
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class User {

  private final String username;
  private final List<String> groups;
  private final UserRule authKeyRule;

  private User(String username, List<String> pGroups, UserRule rule) {
    this.username = username;
    this.groups = pGroups;
    this.authKeyRule = rule;
  }

  public static User fromSettings(Settings s, List<ProxyAuthConfig> proxyAuthConfigs, ESContext context)
      throws ConfigMalformedException {
    String username = s.get("username");
    UserRule authKeyRule = getAuthKeyRuleFrom(s, proxyAuthConfigs, username, context);
    List<String> groups = Lists.newArrayList(s.getAsArray("groups"));
    if (groups.isEmpty())
      throw new ConfigMalformedException("No groups defined for user " + (username != null ? username : "<no name>"));
    return new User(username, groups, authKeyRule);
  }

  private static UserRule getAuthKeyRuleFrom(Settings s, List<ProxyAuthConfig> proxyAuthConfigs,
                                             String username, ESContext context) {
    return AuthKeySyncRule.fromSettings(s, context).map(r -> (UserRule) r).orElseGet(() ->
        AuthKeySha1SyncRule.fromSettings(s, context).map(r -> (UserRule) r).orElseGet(() ->
            AuthKeySha256SyncRule.fromSettings(s, context).map(r -> (UserRule) r).orElseGet(() ->
                ProxyAuthSyncRule.fromSettings(s, proxyAuthConfigs, context).orElseThrow(() ->
                    new ConfigMalformedException("No auth rule defined for user " + (username != null ? username : "<no name>"))
                )
            )
        )
    );
  }

  public String getUsername() {
    return username;
  }

  public UserRule getAuthKeyRule() {
    return authKeyRule;
  }

  public List<String> getGroups() {
    return groups;
  }
} 
