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

package org.elasticsearch.plugin.readonlyrest.acl.blocks;

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.*;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.*;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.*;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {
  private final String name;
  private final Policy policy;
  private final Logger logger;
  private final Set<AsyncRule> conditionsToCheck;
  private boolean authHeaderAccepted;

  public Block(Settings settings,
               List<User> userList,
               List<LdapConfig> ldapList,
               List<ProxyAuthConfig> proxyAuthConfigs,
               List<UserRoleProviderConfig> roleProviderConfigs,
               Logger logger) {
    this.name = settings.get("name");
    String sPolicy = settings.get("type");
    this.logger = logger;
    if (sPolicy == null) {
      throw new RuleConfigurationError(
          "The field \"type\" is mandatory and should be either of " + Block.Policy.valuesString() +
              ". If this field is correct, check the YAML indentation is correct.", null);
    }

    policy = Block.Policy.valueOf(sPolicy.toUpperCase());

    conditionsToCheck = collectRules(settings, userList, proxyAuthConfigs, ldapList, roleProviderConfigs);
    authHeaderAccepted = conditionsToCheck.stream().anyMatch(this::isAuthenticationRule);
  }

  public Set<AsyncRule> getRules() {
    return conditionsToCheck;
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

  /*
   * Check all the conditions of this rule and return a rule exit result
   *
   */
  public CompletableFuture<BlockExitResult> check(RequestContext rc) {
    return checkAsyncRules(rc)
        .thenApply(asyncCheck -> {
          if (asyncCheck != null && asyncCheck) {
            return finishWithMatchResult(rc);
          } else {
            return finishWithNoMatchResult(rc);
          }
        });
  }

  private CompletableFuture<Boolean> checkAsyncRules(RequestContext rc) {
    // async rules should be checked in sequence due to interaction with not thread safe objects like RequestContext
    Set<RuleExitResult> thisBlockHistory = new HashSet<>(conditionsToCheck.size());
    return checkAsyncRulesInSequence(rc, conditionsToCheck.iterator(), thisBlockHistory)
        .thenApply(result -> {
          rc.addToHistory(this, thisBlockHistory);
          return result;
        });
  }

  private CompletableFuture<Boolean> checkAsyncRulesInSequence(RequestContext rc,
                                                               Iterator<AsyncRule> rules,
                                                               Set<RuleExitResult> thisBlockHistory) {
    return FuturesSequencer.runInSeqUntilConditionIsUndone(
        rules,
        rule -> rule.match(rc),
        condExitResult -> {
          thisBlockHistory.add(condExitResult);
          return !condExitResult.isMatch();
        },
        RuleExitResult::isMatch,
        nothing -> true
    );
  }

  private BlockExitResult finishWithMatchResult(RequestContext rc) {
    logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);
    rc.commit();
    return BlockExitResult.match(this);
  }

  private BlockExitResult finishWithNoMatchResult(RequestContext rc) {
    logger.debug(ANSI_YELLOW + "[" + name + "] the request matches no rules in this block: " + rc + ANSI_RESET);
    rc.reset();
    return BlockExitResult.noMatch();
  }

  @Override
  public String toString() {
    return "readonlyrest Rules Block :: { name: '" + name + "', policy: " + policy + "}";
  }

  private Set<AsyncRule> collectRules(Settings s, List<User> userList, List<ProxyAuthConfig> proxyAuthConfigs,
                                      List<LdapConfig> ldapConfigs, List<UserRoleProviderConfig> roleProviderConfigs) {
    Set<AsyncRule> rules = Sets.newLinkedHashSet();
    // Won't add the condition if its configuration is not found

    // Authentication rules must come first because they set the user
    // information which further rules might rely on.
    AuthKeySyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    AuthKeySha1SyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    AuthKeySha256SyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    ProxyAuthSyncRule.fromSettings(s, proxyAuthConfigs).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);

    // Inspection rules next; these act based on properties
    // of the request.
    KibanaAccessSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    HostsSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    XForwardedForSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    ApiKeysSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    SessionMaxIdleSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    UriReSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    MaxBodyLengthSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    MethodsSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    IndicesSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    ActionsSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    GroupsSyncRule.fromSettings(s, userList).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
    SearchlogSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);

    // then we could check potentially slow async rules
    LdapAuthAsyncRule.fromSettings(s, ldapConfigs).ifPresent(rules::add);

    // all authorization rules should be placed before any authentication rule
    ProviderRolesAuthorizationAsyncRule.fromSettings(s, roleProviderConfigs)
        .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s)).ifPresent(rules::add);

    // At the end the sync rule chain are those that can mutate
    // the client request.
    IndicesRewriteSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);

    return rules;
  }

  private boolean isAuthenticationRule(AsyncRule rule) {
    return rule instanceof Authentication ||
        (rule instanceof AsyncRuleAdapter && ((AsyncRuleAdapter)rule).getUnderlying() instanceof Authentication);
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
