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

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.BlockPolicy;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_YELLOW;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {

  private final Logger logger;
  private final String name;
  private final BlockPolicy policy;
  private final Set<AsyncRule> conditionsToCheck;
  private final boolean authHeaderAccepted;

  public Block(BlockSettings settings,
               RulesFactory rulesFactory,
               ESContext context) {
    this.logger = context.logger(getClass());
    this.name = settings.getName();
    this.policy = settings.getPolicy();
    this.conditionsToCheck = settings.getRules().stream()
        .map(rulesFactory::create)
        .collect(Collectors.toSet());
    this.authHeaderAccepted = conditionsToCheck.stream().anyMatch(this::isAuthenticationRule);
  }

  public Set<AsyncRule> getRules() {
    return conditionsToCheck;
  }

  public String getName() {
    return name;
  }

  public BlockPolicy getPolicy() {
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
        ruleExitResult -> {
          thisBlockHistory.add(ruleExitResult);
          return !ruleExitResult.isMatch();
        },
        RuleExitResult::isMatch,
        nothing -> true
    );
  }

  private BlockExitResult finishWithMatchResult(RequestContext rc) {
    logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);

    return BlockExitResult.match(this);
  }

  private BlockExitResult finishWithNoMatchResult(RequestContext rc) {
    logger.debug(ANSI_YELLOW + "[" + name + "] the request matches no rules in this block: " + rc + ANSI_RESET);
    return BlockExitResult.noMatch();
  }

  private boolean isAuthenticationRule(AsyncRule rule) {
    return rule instanceof Authentication ||
        (rule instanceof AsyncRuleAdapter && ((AsyncRuleAdapter) rule).getUnderlying() instanceof Authentication);
  }

  @Override
  public String toString() {
    return "readonlyrest Rules Block :: { name: '" + name + "', policy: " + policy + "}";
  }

  // todo: remove
  // todo: implement order in separate comparator class
//  private Set<AsyncRule> collectRules(Settings s, List<User> userList, List<ProxyAuthConfig> proxyAuthConfigs,
//                                      LdapConfigs ldapConfigs, List<UserGroupProviderConfig> groupsProviderConfigs,
//                                      List<ExternalAuthenticationServiceConfig> externalAuthenticationServiceConfigs) {
//    Set<AsyncRule> rules = Sets.newLinkedHashSet();
//    // Won't add the condition if its configuration is not found
//
//    // Authentication rules must come first because they set the user
//    // information which further rules might rely on.
//    AuthKeySyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    AuthKeySha1SyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    AuthKeySha256SyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    ProxyAuthSyncRule.fromSettings(s, proxyAuthConfigs, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//
//    // Inspection rules next; these act based on properties
//    // of the request.
//    KibanaAccessSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    HostsSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    XForwardedForSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    ApiKeysSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    SessionMaxIdleSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    UriReSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    MaxBodyLengthSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    MethodsSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    IndicesSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    ActionsSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    GroupsSyncRule.fromSettings(s, userList).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    SearchlogSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//    VerbositySyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//
//    // then we could check potentially slow async rules
//    LdapAuthAsyncRule.fromSettings(s, ldapConfigs, context).ifPresent(rules::add);
//    LdapAuthenticationAsyncRule.fromSettings(s, ldapConfigs, context)
//        .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s, context)).ifPresent(rules::add);
//    ExternalAuthenticationAsyncRule.fromSettings(s, externalAuthenticationServiceConfigs, context)
//        .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s, context)).ifPresent(rules::add);
//
//    // all authorization rules should be placed before any authentication rule
//    LdapAuthorizationAsyncRule.fromSettings(s, ldapConfigs, context)
//        .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s, context)).ifPresent(rules::add);
//    GroupsProviderAuthorizationAsyncRule.fromSettings(s, groupsProviderConfigs, context)
//        .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s, context)).ifPresent(rules::add);
//
//    // At the end the sync rule chain are those that can mutate
//    // the client request.
//    IndicesRewriteSyncRule.fromSettings(s, context).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);
//
//    return rules;
//  }

}
