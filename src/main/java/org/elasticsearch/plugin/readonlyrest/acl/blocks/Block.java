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
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.RuleConfigurationError;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.*;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.*;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.*;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;
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

        initSyncConditions(s, userList);
        initAsyncConditions(s);
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
    public ListenableFuture<BlockExitResult> check(RequestContext rc) {
        boolean syncCheck = checkSyncRules(rc);
        return Futures.transform(
                checkAsyncRules(rc),
                asyncCheck -> {
                    if (asyncCheck && syncCheck) {
                        logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);
                        rc.commit();
                        return new BlockExitResult(this, true);
                    } else {
                        logger.debug(ANSI_YELLOW + "[" + name + "] the request matches no rules in this block: " + rc + ANSI_RESET);
                        rc.reset();
                        return BlockExitResult.NO_MATCH;
                    }
                }
        );
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

    private ListenableFuture<Boolean> checkAsyncRules(RequestContext rc) {
        // async rules should be checked in sequence due to interaction with not thread safe objects like RequestContext
        Set<RuleExitResult> thisBlockHistory = new HashSet<>(asyncConditionsToCheck.size());
        return Futures.transform(
                checkAsyncRulesInSequence(rc, asyncConditionsToCheck.iterator(), thisBlockHistory),
                result -> {
                    rc.addToHistory(this, thisBlockHistory);
                    return result;
                });
    }

    private ListenableFuture<Boolean> checkAsyncRulesInSequence(RequestContext rc,
                                                                Iterator<AsyncRule> rules,
                                                                Set<RuleExitResult> thisBlockHistory) {
        return FuturesSequencer.runInSeqWithResult(
                rules,
                rule -> rule.match(rc),
                (condExitResult, acc) -> {
                    thisBlockHistory.add(condExitResult);
                    return acc && (condExitResult != null ? condExitResult.isMatch() : false);
                },
                true);
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

    private void initSyncConditions(Settings s, List<Settings> userList) {
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

    private void initAsyncConditions(Settings s) {
        // todo: implement
        asyncConditionsToCheck.add(new AsyncRule(s) {
            @Override
            public ListenableFuture<RuleExitResult> match(RequestContext rc) {
                RuleExitResult result = rc.getUri().contains("blog") ? MATCH : NO_MATCH;
                return Futures.immediateFailedFuture(new Exception("failed"));
            }
        });
    }

}
