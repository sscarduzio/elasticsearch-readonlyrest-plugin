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

package org.elasticsearch.plugin.readonlyrest.acl.blocks;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeyRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReRule;
import org.elasticsearch.plugin.readonlyrest.wiring.ThreadRepo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_YELLOW;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {
  private final String name;
  private final Policy policy;
  private Logger logger;
  private boolean authHeaderAccepted = false;
  private Set<Rule> conditionsToCheck = Sets.newHashSet();

  public Block(Settings s, List<Settings> userList, Logger logger) {
    this.name = s.get("name");
    String sPolicy = s.get("type");
    this.logger = logger;
    if (sPolicy == null) {
      throw new RuleConfigurationError(
          "The field \"type\" is mandatory and should be either of " + Block.Policy.valuesString() +
              ". If this field is correct, check the YAML indentation is correct.", null);
    }


    policy = Block.Policy.valueOf(sPolicy.toUpperCase());

    // Won't add the condition if its configuration is not found
    try {
      conditionsToCheck.add(new KibanaAccessRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new HostsRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new XForwardedForRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new ApiKeysRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new AuthKeyRule(s));
      authHeaderAccepted = true;
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new AuthKeySha1Rule(s));
      authHeaderAccepted = true;
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new SessionMaxIdleRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new UriReRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new MaxBodyLengthRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new MethodsRule(s));
    } catch (RuleNotConfiguredException e) {
    }

    try {
      conditionsToCheck.add(new IndicesRule(s));
    } catch (RuleNotConfiguredException e) {
    }
    try {
      conditionsToCheck.add(new ActionsRule(s));
    } catch (RuleNotConfiguredException e) {
    }

    try {
      conditionsToCheck.add(new GroupsRule(s, userList));
    } catch (RuleNotConfiguredException e) {
    }
  }

  public String getName() {
    return name;
  }

  public Policy getPolicy() {
    return policy;
  }

  public boolean isAuthHeaderAccepted() {
    return authHeaderAccepted;
  }

  public BlockExitResult check(RequestContext rc) {
    boolean match = true;
    Map<String,String> thisBlockHistory = new HashMap<>(conditionsToCheck.size());
    for (Rule condition : conditionsToCheck) {
      // Exit at the first rule that matches the request
      RuleExitResult condExitResult = condition.match(rc);

      // Track rule history
      thisBlockHistory.put(condition.getKey(), condExitResult.isMatch().toString());

      // a block matches if ALL rules match
      match &= condExitResult.isMatch();
    }

    Joiner.MapJoiner j = Joiner.on(",").withKeyValueSeparator("=");
    ThreadRepo.history.get().put(getName(), "[" + j.join(thisBlockHistory) + "]");

    if (match) {
      logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);
      return new BlockExitResult(this, true);
    }
    logger.debug(ANSI_YELLOW + "[" + name + "] the request matches no rules in this block: " + rc + ANSI_RESET);

    return BlockExitResult.NO_MATCH;
  }

  /*
   * Check all the conditions of this rule and return a rule exit result
   *
   */

  @Override
  public String toString() {
    return "readonlyrest Rules Block :: { name: '" + name + "', policy: " + policy + "}";
  }

  public enum Policy {
    ALLOW, FORBID;

    public static String valuesString() {
      StringBuilder sb = new StringBuilder();
      for (Policy v : values()) {
        sb.append(v.toString()).append(",");
      }
      sb.deleteCharAt(sb.length() - 1);
      return sb.toString();
    }
  }
}
