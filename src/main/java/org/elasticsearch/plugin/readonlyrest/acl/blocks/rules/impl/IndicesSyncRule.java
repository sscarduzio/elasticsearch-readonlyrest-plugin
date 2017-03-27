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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesSyncRule extends SyncRule {

  private final Logger logger = Loggers.getLogger(this.getClass());

  private MatcherWithWildcards configuredWildcards;

  public IndicesSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    configuredWildcards = MatcherWithWildcards.fromSettings(s, getKey());
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    logger.debug("Stage -1");
    if (!rc.involvesIndices()) {
      return MATCH;
    }

    Set<String> indices = rc.getCurrentIndices();

    // 1. Requesting none or all the indices means requesting allowed indices that exist..
    logger.debug("Stage 0");
    if (indices.size() == 0 || indices.contains("_all") || indices.contains("*")) {
      Set<String> allowedIdxs = configuredWildcards.filter(rc.getAvailableIndicesAndAliases());
      if (allowedIdxs.size() > 0) {
        rc.setIndices(allowedIdxs);
        return MATCH;
      }
      return NO_MATCH;
    }

    if (rc.isReadRequest()) {

      // Handle simple case of single index
      logger.debug("Stage 1");
      if (indices.size() == 1) {
        if (configuredWildcards.match(indices.iterator().next())) {
          return MATCH;
        }
      }

      // ----- Now you requested SOME indices, let's see if and what we can allow in..

      // 2. All indices match by wildcard?
      logger.debug("Stage 2");
      if (configuredWildcards.filter(indices).size() == indices.size()) {
        return MATCH;
      }

      logger.debug("Stage 2.1");
      // 2.1 Detect non-wildcard requested indices that do not exist and return 404 (compatibility with vanilla ES)
      Set<String> real = rc.getAvailableIndicesAndAliases();
      for (final String idx : indices) {
        if (!idx.contains("*") && !real.contains(idx)) {
          Set<String> nonExistingIndex = new HashSet<>(1);
          nonExistingIndex.add(idx);
          rc.setIndices(nonExistingIndex);
          return MATCH;
        }
      }

      // 3. indices match by reverse-wildcard?
      // Expand requested indices to a subset of indices available in ES
      logger.debug("Stage 3");
      Set<String> expansion = rc.getExpandedIndices();

      // 4. Your request expands to no actual index, fine with me, it will return 404 on its own!
      logger.debug("Stage 4");
      if (expansion.size() == 0) {
        return MATCH;
      }

      // ------ Your request expands to one or many available indices, let's see which ones you are allowed to request..
      Set<String> allowedExpansion = configuredWildcards.filter(expansion);

      // 5. You requested some indices, but NONE were allowed
      logger.debug("Stage 5");
      if (allowedExpansion.size() == 0) {
        // #TODO should I set indices to rule wildcards?
        return NO_MATCH;
      }

      // 6. You requested some indices, I can allow you only SOME (we made sure the allowed set is not empty!).
      logger.debug("Stage 6");
      rc.setIndices(allowedExpansion);
      return MATCH;
    }

    // Write requests
    else {

      // Handle <no-index> (#TODO LEGACY)
      logger.debug("Stage 7");
      if (indices.size() == 0 && configuredWildcards.getMatchers().contains("<no-index>")) {
        return MATCH;
      }

      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug("Stage 8");
      for (String idx : indices) {
        if (!configuredWildcards.match(idx)) {
          return NO_MATCH;
        }
      }

      // Conditions are satisfied
      return MATCH;
    }
  }
}
