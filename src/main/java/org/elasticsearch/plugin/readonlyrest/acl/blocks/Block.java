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
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.LdapConfigs;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.VerbositySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.XForwardedForSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_YELLOW;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {
  private final String name;
  private final Policy policy;
  private final ESLogger logger;
  private final Set<AsyncRule> conditionsToCheck;
  private boolean authHeaderAccepted;

  public Block(Settings settings,
               List<User> userList,
               LdapConfigs ldapConfigs,
               List<ProxyAuthConfig> proxyAuthConfigs,
//               List<UserGroupProviderConfig> groupsProviderConfigs,
//               List<ExternalAuthenticationServiceConfig> externalAuthenticationServiceConfigs,
               ESLogger logger) {
    this.name = settings.get("name");
    String sPolicy = settings.get("type", Policy.ALLOW.name());
    this.logger = logger;

    policy = Block.Policy.valueOf(sPolicy.toUpperCase());

    conditionsToCheck = collectRules(settings, userList, proxyAuthConfigs, ldapConfigs
                                     //groupsProviderConfigs,
//                                     externalAuthenticationServiceConfigs
    );
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
        }
        else {
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

  @Override
  public String toString() {
    return "readonlyrest Rules Block :: { name: '" + name + "', policy: " + policy + "}";
  }

  private Set<AsyncRule> collectRules(Settings s, List<User> userList, List<ProxyAuthConfig> proxyAuthConfigs,
                                      LdapConfigs ldapConfigs
                                      //List<UserGroupProviderConfig> groupsProviderConfigs,
                                      //                                    List<ExternalAuthenticationServiceConfig> externalAuthenticationServiceConfigs
  ) {
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
    VerbositySyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);

    // then we could check potentially slow async rules
    LdapAuthAsyncRule.fromSettings(s, ldapConfigs).ifPresent(rules::add);
    LdapAuthenticationAsyncRule.fromSettings(s, ldapConfigs)
      .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s)).ifPresent(rules::add);
    //   ExternalAuthenticationAsyncRule.fromSettings(s, externalAuthenticationServiceConfigs)
    //     .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s)).ifPresent(rules::add);

    // all authorization rules should be placed before any authentication rule
    LdapAuthorizationAsyncRule.fromSettings(s, ldapConfigs)
      .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s)).ifPresent(rules::add);
    //GroupsProviderAuthorizationAsyncRule.fromSettings(s, groupsProviderConfigs)
    //  .map(rule -> wrapInCacheIfCacheIsEnabled(rule, s)).ifPresent(rules::add);

    // At the end the sync rule chain are those that can mutate
    // the client request.
    IndicesRewriteSyncRule.fromSettings(s).map(AsyncRuleAdapter::wrap).ifPresent(rules::add);

    return rules;
  }

  private boolean isAuthenticationRule(AsyncRule rule) {
    return rule instanceof Authentication ||
      (rule instanceof AsyncRuleAdapter && ((AsyncRuleAdapter) rule).getUnderlying() instanceof Authentication);
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
