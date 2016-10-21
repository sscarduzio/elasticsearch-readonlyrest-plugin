package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Joiner;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesRule extends Rule {

  private final static ESLogger logger = Loggers.getLogger(IndicesRule.class);

  protected MatcherWithWildcards configuredWildcards;

  public IndicesRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    configuredWildcards = MatcherWithWildcards.fromSettings(s, KEY);
  }

  public Set<String> getRealSearchableIndicesFromWildcards(RequestContext rc, Set<String> allowedWildCards) {
    Set<String> availableIndices = rc.getAvailableIndicesAndAliases();
    MatcherWithWildcards matcher = new MatcherWithWildcards(allowedWildCards);
    Set<String> allowedIndicesSubset = new HashSet<>();
    // Calculate the subset of available indices that match the allowed indices list (may contain wildcards)
    for (String availableIndex : availableIndices) {
      if (matcher.match(availableIndex)) {
        allowedIndicesSubset.add(availableIndex);
      }
    }
    if (logger.isDebugEnabled()) {
      String availableIdxs = Joiner.on(',').skipNulls().join(availableIndices);
      String allowedIdxs = Joiner.on(',').skipNulls().join(allowedIndicesSubset);
      logger.debug("Available indices: [" + availableIdxs + "] of which allowed: [" + allowedIdxs + "]");
    }
    return allowedIndicesSubset;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    Set<String> indices = rc.getIndices();
    if (indices.size() == 0 && configuredWildcards.getMatchers().contains("<no-index>")) {
      return MATCH;
    }

    Set<String> allowedIndices = getRealSearchableIndicesFromWildcards(rc, configuredWildcards.getMatchers());

    boolean hasAll = false;

    for (String idx : rc.getIndices()) {

      // For searches, we need to match to existing indices
      if (rc.getActionRequest() instanceof SearchRequest) {
        if ("_all".equals(idx)) {
          hasAll = true;
          continue;
        }
        if (!allowedIndices.contains(idx)) {
          logger.debug("This request uses the indices '" + Arrays.toString(rc.getIndices().toArray()) + "' at least one of which ( " + idx + ") is on the list: " + Arrays.toString(allowedIndices.toArray()));
          return NO_MATCH;
        }
      }
      // Writes et al
      else {
        if (!configuredWildcards.match(idx)) {
          return NO_MATCH;
        }
      }
    }

    if (hasAll) {
      // Set the indices to permitted ones just if this rule actually has any valid indices to set
      if (!configuredWildcards.getMatchers().contains("<no-index>") && configuredWildcards.getMatchers().size() > 0) {
        rc.setIndices(allowedIndices);
        return MATCH;
      } else {
        return NO_MATCH;
      }
    }

    return MATCH;
  }

}
