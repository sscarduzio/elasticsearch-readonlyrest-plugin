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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.oauth.OAuthToken;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

/**
 * A GroupsSyncRule checks if a request containing Basic Authentication credentials
 * matches a user in one of the specified groups.
 *
 * @author Christian Henke (maitai@users.noreply.github.com)
 */
public class GroupsSyncRule extends SyncRule {

  private final List<User> users;
  private final List<String> groups;
  private  boolean hasReplacements = false;
  private String kibanaGroup = "Kibana";
  private String adminGroup = "Admin";
  
  public GroupsSyncRule(Settings s, List<User> userList) throws RuleNotConfiguredException {
    super();

    users = userList;
    String[] pGroups = s.getAsArray(this.getKey());
    if (pGroups != null && pGroups.length > 0) {
      for(int i = 0; i < pGroups.length; i++){
        if(pGroups[i] != null && pGroups[i].contains("@")){
          hasReplacements = true;
          break;
        }
      }
      this.groups = Arrays.asList(pGroups);
    }
    else {
      throw new RuleNotConfiguredException();
    }
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
    if (!ConfigurationHelper.isOAuthEnabled()) {
	  for (User user : this.users) {
        if (user.getAuthKeyRule().match(rc).isMatch()) {
          List<String> commonGroups = new ArrayList<>(user.getGroups());

          List<String> groupsInThisRule;
          if(hasReplacements){
            groupsInThisRule = new ArrayList<>(this.groups.size());
            for(String g : this.groups){
              // won't add if applyVariables doesn't find all replacements
              rc.applyVariables(g).map(groupsInThisRule::add);
            }
          }
          else{
            groupsInThisRule = this.groups;
          }

          commonGroups.retainAll(groupsInThisRule);
          if (!commonGroups.isEmpty()) {
            return MATCH;
          }
        }
	  }
    } else {
    	OAuthToken token = rc.getToken();
		List<String> commonGroups = new ArrayList<>(this.groups);
		if (commonGroups.contains(kibanaGroup) && token == null)
			return MATCH;
		if (commonGroups == null || commonGroups.isEmpty() || token == null || token.getRoles() == null)
			return NO_MATCH;
		commonGroups.retainAll(token.getRoles());
		if (!commonGroups.isEmpty() && token.getRoles().contains(adminGroup))
			return MATCH;
//		else 
			//if (commonGroups.size() == token.getRoles().size())
		if (!commonGroups.isEmpty())
			return MATCH;
		return NO_MATCH;
    }
    return NO_MATCH;
  }

}
