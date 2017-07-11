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
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.BlockPolicy;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesFactory;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RulesOrdering;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authorization;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Verbosity;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.BlockSettings;
import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;
import org.elasticsearch.plugin.readonlyrest.utils.RulesUtils;

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

  private final LoggerShim logger;
  private final BlockSettings settings;
  private final List<AsyncRule> rules;
  private final boolean authHeaderAccepted;

  public Block(BlockSettings settings,
               RulesFactory rulesFactory,
               ESContext context) {
    this.logger = context.logger(getClass());
    this.settings = settings;
    this.rules = settings.getRules().stream()
      .map(rulesFactory::create)
      .collect(Collectors.toList());

    Set<Class<?>> phantomTypes = rules.stream()
      .flatMap(r -> RulesUtils.ruleHasPhantomTypes(r, Sets.newHashSet(Authorization.class, Authentication.class)).stream())
      .collect(Collectors.toSet());

    boolean containsAuthorization = phantomTypes.contains(Authorization.class);
    boolean containsAuthentication = phantomTypes.contains(Authentication.class);

    // Fail if this block contains authZ, but not authC
    if (containsAuthorization && !containsAuthentication) {
      throw new SettingsMalformedException(
        "The '" + this.getName() + "' block contains an authorization rule, but not an authentication rule. " +
          "This does not mean anything if you don't also set some authentication rule");
    }

    this.authHeaderAccepted = containsAuthentication || containsAuthorization;

    this.rules.sort(new RulesOrdering());
  }

  public List<AsyncRule> getRules() {
    return rules;
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
        }
        else {
          return finishWithNoMatchResult(rc);
        }
      });
  }

  private CompletableFuture<Boolean> checkAsyncRules(RequestContext rc) {
    // async rules should be checked in sequence due to interaction with not thread safe objects like RequestContext
    Set<RuleExitResult> thisBlockHistory = new HashSet<>(rules.size());
    return checkAsyncRulesInSequence(rc, rules.iterator(), thisBlockHistory)
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


  @Override
  public String toString() {
    return "{ name: '" + settings.getName() + "', policy: " + settings.getPolicy() + "}";
  }

}
