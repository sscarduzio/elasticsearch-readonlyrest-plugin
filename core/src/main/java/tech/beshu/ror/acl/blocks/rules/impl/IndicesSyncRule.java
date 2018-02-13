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

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.IndicesRuleSettings;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesSyncRule extends SyncRule {

  private final IndicesRuleSettings settings;
  private final LoggerShim logger;
  private final MatcherWithWildcards matcherNoVar;

  public IndicesSyncRule(IndicesRuleSettings s, ESContext context) {
    this.logger = context.logger(getClass());
    this.settings = s;
    if (!s.hasVariables()) {
      this.matcherNoVar = new MatcherWithWildcards(s.getIndicesUnwrapped());
    }
    else {
      this.matcherNoVar = null;
    }
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    logger.debug("Stage -1");
    if (!rc.involvesIndices() || matcherNoVar.getMatchers().contains("*")) {
      return MATCH;
    }

    return canPass(rc) ? MATCH : NO_MATCH;
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  // Is a request or sub-request free from references to any forbidden indices?
  private <T extends RequestContext> boolean canPass(T src) {

    MatcherWithWildcards matcher = matcherNoVar != null ? matcherNoVar : new MatcherWithWildcards(
      settings.getIndices().stream()
        .map(v -> v.getValue(src))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toSet())
    );

    Set<String> indices = Sets.newHashSet(src.getIndices());

    // 1. Requesting none or all the indices means requesting allowed indices that exist.
    logger.debug("Stage 0");
    if (indices.size() == 0 || indices.contains("_all") || indices.contains("*")) {
      Set<String> allowedIdxs = matcher.filter(src.getAllIndicesAndAliases());
      if (allowedIdxs.size() > 0) {
        indices.clear();
        indices.addAll(allowedIdxs);
        src.setIndices(indices);
        return true;
      }
      return false;
    }

    if (src.isReadRequest()) {

      // Handle simple case of single index
      logger.debug("Stage 1");
      if (indices.size() == 1) {
        if (matcher.match(indices.iterator().next())) {
          return true;
        }
      }

      // ----- Now you requested SOME indices, let's see if and what we can allow in.

      // 2. All indices match by wildcard?
      logger.debug("Stage 2");
      if (matcher.filter(indices).size() == indices.size()) {
        return true;
      }

      logger.debug("Stage 2.1");
      // 2.1 Detect at least 1 non-wildcard requested indices that do not exist, ES will naturally return 404, our job is done.
      Set<String> nonExistent = Sets.newHashSet();
      Set<String> real = src.getAllIndicesAndAliases();
      for (final String idx : indices) {
        if (!idx.contains("*") && !real.contains(idx)) {
          nonExistent.add(idx);
        }
      }
      if (!nonExistent.isEmpty()) {
        if (!src.isComposite()) {
          // This goes to 404 naturally, so let it through
          return true;
        }

        // Continue evaluation ignoring non-existent indices
        indices.removeAll(nonExistent);

        // This is going to be a natural 200 with zero results, let through safely
        if (indices.isEmpty()) {
          return true;
        }
      }

      // 3. indices match by reverse-wildcard?
      // Expand requested indices to a subset of indices available in ES
      logger.debug("Stage 3");
      Set<String> expansion = src.getExpandedIndices();

      // 4. Your request expands to no actual index, fine with me, it will return 404 on its own!
      logger.debug("Stage 4");
      if (expansion.size() == 0) {
        return true;
      }

      // ------ Your request expands to one or many available indices, let's see which ones you are allowed to request..
      Set<String> allowedExpansion = matcher.filter(expansion);

      // 5. You requested some indices, but NONE were allowed
      logger.debug("Stage 5");
      if (allowedExpansion.size() == 0) {
        // #TODO should I set indices to rule wildcards?
        return false;
      }

      // 6. You requested some indices, I can allow you only SOME (we made sure the allowed set is not empty!).
      logger.debug("Stage 6");
      src.setIndices(allowedExpansion);
      return true;
    }

    // Write requests
    else {

      // Handle <no-index> (#TODO LEGACY)
      logger.debug("Stage 7");
      if (indices.size() == 0 && matcher.getMatchers().contains("<no-index>")) {
        return true;
      }

      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug("Stage 8");
      for (String idx : indices) {
        if (!matcher.match(idx)) {
          return false;
        }
      }

      // Conditions are satisfied
      return true;
    }
  }
}
