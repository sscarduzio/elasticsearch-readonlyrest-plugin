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
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.*;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {
  private final String name;
  private final Policy policy;
  private final Logger logger;
  private final Set<SyncRule> syncConditionsToCheck;
  private final Set<AsyncRule> asyncConditionsToCheck;
  private boolean authHeaderAccepted = false;

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

    // now rules are sorted in two separate collections. This solution will case problem when we have sync authorization
    // rule and async authentication. For now it's enough, but should be refactored in future.
    syncConditionsToCheck = collectSyncRules(settings, userList, proxyAuthConfigs).stream()
        .sorted(RulesComparator.INSTANCE)
        .collect(Collectors.toSet());
    asyncConditionsToCheck = collectAsyncRules(settings, ldapList, roleProviderConfigs).stream()
        .sorted(RulesComparator.INSTANCE)
        .collect(Collectors.toSet());
  }

  public Set<SyncRule> getSyncRules() {
    return syncConditionsToCheck;
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
    boolean syncCheck = checkSyncRules(rc);
    if (syncCheck) {
      return checkAsyncRules(rc)
          .thenApply(asyncCheck -> {
            if (asyncCheck != null && asyncCheck) {
              return finishWithMatchResult(rc);
            } else {
              return finishWithNoMatchResult(rc);
            }
          });
    } else {
      return CompletableFuture.completedFuture(
          finishWithNoMatchResult(rc)
      );
    }
  }

  private boolean checkSyncRules(RequestContext rc) {
    boolean match = true;
    Set<RuleExitResult> thisBlockHistory = new HashSet<>(syncConditionsToCheck.size());

    for (SyncRule condition : syncConditionsToCheck) {
      // Exit at the first rule that matches the request
      RuleExitResult condExitResult = condition.match(rc);
      // Log history
      thisBlockHistory.add(condExitResult);
      // a block matches if ALL rules match
      match &= condExitResult.isMatch();
    }

    rc.addToHistory(this, thisBlockHistory);

    return match;
  }

  private CompletableFuture<Boolean> checkAsyncRules(RequestContext rc) {
    // async rules should be checked in sequence due to interaction with not thread safe objects like RequestContext
    Set<RuleExitResult> thisBlockHistory = new HashSet<>(asyncConditionsToCheck.size());
    return checkAsyncRulesInSequence(rc, asyncConditionsToCheck.iterator(), thisBlockHistory)
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

  private Set<SyncRule> collectSyncRules(Settings s, List<User> userList, List<ProxyAuthConfig> proxyAuthConfigs) {
    Set<SyncRule> rules = Sets.newHashSet();
    // Won't add the condition if its configuration is not found
    try {
      rules.add(new KibanaAccessSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new HostsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new XForwardedForSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new ApiKeysSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new AuthKeySyncRule(s));
      authHeaderAccepted = true;
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new AuthKeySha1SyncRule(s));
      authHeaderAccepted = true;
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new AuthKeySha256SyncRule(s));
      authHeaderAccepted = true;
    } catch (RuleNotConfiguredException ignored) {
    }
    ProxyAuthSyncRule.fromSettings(s, proxyAuthConfigs).ifPresent(rules::add);
    try {
      rules.add(new SessionMaxIdleSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new UriReSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new MaxBodyLengthSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new MethodsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new IndicesSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new ActionsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new GroupsSyncRule(s, userList));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new IndicesRewriteSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new KibanaHideAppsSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    try {
      rules.add(new SearchlogSyncRule(s));
    } catch (RuleNotConfiguredException ignored) {
    }
    return rules;
  }

  private Set<AsyncRule> collectAsyncRules(Settings s, List<LdapConfig> ldapConfigs, List<UserRoleProviderConfig> roleProviderConfigs) {
    Set<AsyncRule> rules = Sets.newHashSet();

    LdapAuthAsyncRule.fromSettings(s, ldapConfigs).ifPresent(rule -> {
      rules.add(rule);
      authHeaderAccepted = true;
    });
    ProviderRolesAuthorizationAsyncRule.fromSettings(s, roleProviderConfigs)
        .ifPresent(rule -> rules.add(wrapInCacheIfCacheIsEnabled(rule, s)));

    return rules;
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
