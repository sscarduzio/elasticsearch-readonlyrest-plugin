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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ExternalAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter;
import org.elasticsearch.plugin.readonlyrest.acl.BlockPolicy;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesOrdering;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.Constants.ANSI_CYAN;
import static org.elasticsearch.plugin.readonlyrest.Constants.ANSI_RESET;
import static org.elasticsearch.plugin.readonlyrest.Constants.ANSI_YELLOW;

/**
 * Created by sscarduzio on 13/02/2016.
 */
public class Block {

  private final Logger logger;
  private final BlockSettings settings;
  private final List<AsyncRule> conditionsToCheck;
  private final boolean authHeaderAccepted;

  public Block(BlockSettings settings,
               RulesFactory rulesFactory,
               ESContext context) {
    this.logger = context.logger(getClass());
    this.settings = settings;
    this.conditionsToCheck = settings.getRules().stream()
        .map(rulesFactory::create)
        .collect(Collectors.toList());
    this.conditionsToCheck.sort(new RulesOrdering());
    this.authHeaderAccepted = conditionsToCheck.stream().anyMatch(this::isAuthenticationRule);
  }

  public List<AsyncRule> getRules() {
    return conditionsToCheck;
  }

  public String getName() {
    return settings.getName();
  }

  public BlockPolicy getPolicy() {
    return settings.getPolicy();
  }

  public Verbosity getVerbosity() {
    return settings.getVerbosity();
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
            return finishWithMatchResult();
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

  private BlockExitResult finishWithMatchResult() {
    logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);
    return BlockExitResult.match(this);
  }

  private BlockExitResult finishWithNoMatchResult(RequestContext rc) {
    logger.debug(ANSI_YELLOW + "[" + settings.getName() + "] the request matches no rules in this block: " + rc + ANSI_RESET);
    return BlockExitResult.noMatch();
  }

  private boolean isAuthenticationRule(AsyncRule rule) {
    return
      rule instanceof Authentication ||
      rule instanceof ExternalAuthenticationAsyncRule ||
        (rule instanceof AsyncRuleAdapter && ((AsyncRuleAdapter) rule).getUnderlying() instanceof Authentication) ||
        (rule instanceof AsyncRuleAdapter && ((AsyncRuleAdapter) rule).getUnderlying() instanceof GroupsSyncRule);
  }

  @Override
  public String toString() {
    return "readonlyrest Rules Block :: { name: '" + settings.getName() + "', policy: " + settings.getPolicy() + "}";
  }

}
