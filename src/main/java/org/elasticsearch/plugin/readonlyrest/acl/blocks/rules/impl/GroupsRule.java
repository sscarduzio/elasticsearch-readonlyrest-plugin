package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserNotConfiguredException;

/**
 * A GroupsRule checks if a request containing Basic Authentication credentials 
 * matches a user in one of the specified groups.
 * 
 * @author Christian Henke <maitai@users.noreply.github.com>
 */
public class GroupsRule extends Rule {

  private final List<User> users = new ArrayList<>();
  private final List<String> groups;

  public GroupsRule(Settings s, List<Settings> userList) throws RuleNotConfiguredException {
    super(s);

    String[] pGroups = s.getAsArray(this.KEY);
    if (pGroups != null && pGroups.length > 0) {
      this.groups = Arrays.asList(pGroups);
    } else {
      throw new RuleNotConfiguredException();
    }
    for (Settings userProperties : userList) {
      try {
        this.users.add(new User(userProperties));
      } catch (UserNotConfiguredException e) {
      }
    }
    if (this.users.isEmpty()) {
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
