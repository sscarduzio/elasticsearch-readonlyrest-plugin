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

import com.google.common.collect.Sets;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.XForwardedForSyncRule;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;


import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_YELLOW;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {
    private final String name;
    private final Policy policy;
    private final Logger logger;
    private boolean authHeaderAccepted = false;

    private final Set<SyncRule> syncConditionsToCheck = Sets.newHashSet();
    private final Set<AsyncRule> asyncConditionsToCheck = Sets.newHashSet();

    public Block(Settings s, List<User> userList, List<LdapConfig> ldapList, Logger logger) {
        this.name = s.get("name");
        String sPolicy = s.get("type");
        this.logger = logger;
        if (sPolicy == null) {
            throw new RuleConfigurationError(
                    "The field \"type\" is mandatory and should be either of " + Block.Policy.valuesString() +
                            ". If this field is correct, check the YAML indentation is correct.", null);
        }

        policy = Block.Policy.valueOf(sPolicy.toUpperCase());

        initSyncConditions(s, userList);
        initAsyncConditions(s, ldapList);
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
        return BlockExitResult.Match(this);
    }

    private BlockExitResult finishWithNoMatchResult(RequestContext rc) {
        logger.debug(ANSI_YELLOW + "[" + name + "] the request matches no rules in this block: " + rc + ANSI_RESET);
        rc.reset();
        return BlockExitResult.NoMatch();
    }

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

    private void initSyncConditions(Settings s, List<User> userList) {
        // Won't add the condition if its configuration is not found
        try {
            syncConditionsToCheck.add(new KibanaAccessSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new HostsSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new XForwardedForSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new ApiKeysSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new AuthKeySyncRule(s));
            authHeaderAccepted = true;
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new AuthKeySha1SyncRule(s));
            authHeaderAccepted = true;
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new AuthKeySha256SyncRule(s));
            authHeaderAccepted = true;
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new SessionMaxIdleSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new UriReSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new MaxBodyLengthSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new MethodsSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new IndicesSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new ActionsSyncRule(s));
        } catch (RuleNotConfiguredException ignored) {
        }
        try {
            syncConditionsToCheck.add(new GroupsSyncRule(s, userList));
        } catch (RuleNotConfiguredException ignored) {
        }
    }

    private void initAsyncConditions(Settings s, List<LdapConfig> ldapConfigs) {
        LdapAuthAsyncRule.fromSettings(s, ldapConfigs).map(rule -> {
            asyncConditionsToCheck.add(rule);
            authHeaderAccepted = true;
            return true;
        });
    }

}
