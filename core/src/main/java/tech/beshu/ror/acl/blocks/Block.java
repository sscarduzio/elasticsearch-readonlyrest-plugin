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

package tech.beshu.ror.acl.blocks;

import static tech.beshu.ror.commons.Constants.ANSI_CYAN;
import static tech.beshu.ror.commons.Constants.ANSI_RESET;
import static tech.beshu.ror.commons.Constants.ANSI_YELLOW;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import tech.beshu.ror.acl.BlockPolicy;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.RulesFactory;
import tech.beshu.ror.acl.blocks.rules.RulesOrdering;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.commons.Verbosity;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.BlockSettings;
import tech.beshu.ror.utils.FuturesSequencer;
import tech.beshu.ror.utils.RulesUtils;

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
  
  public Optional<String> getFilter() {
	  return settings.getFilter();
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
      rule -> rule.match(rc).exceptionally(e -> {
        logger.error(getName() + ": " + rule.getKey() + " rule matching got an error", e);
        return new RuleExitResult(false, rule);
      }),
      ruleExitResult -> {
        thisBlockHistory.add(ruleExitResult);
        return !ruleExitResult.isMatch();
      },
      RuleExitResult::isMatch,
      nothing -> true
    );
  }

  private BlockExitResult finishWithMatchResult() {
    if (logger.isDebugEnabled()) logger.debug(ANSI_CYAN + "matched " + this + ANSI_RESET);
    return BlockExitResult.match(this);
  }

  private BlockExitResult finishWithNoMatchResult(RequestContext rc) {
    if (logger.isDebugEnabled())
      logger.debug(ANSI_YELLOW + "[" + settings.getName() + "] the request matches no rules in this block: " + rc + ANSI_RESET);
    return BlockExitResult.noMatch();
  }


  @Override
  public String toString() {
    return "{ name: '" + settings.getName() + "', policy: " + settings.getPolicy() + "}";
  }

}
