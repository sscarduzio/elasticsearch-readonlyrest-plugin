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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesRule extends Rule {

  private final  Logger logger = Loggers.getLogger(this.getClass());

  protected MatcherWithWildcards configuredWildcards;

  public IndicesRule(Settings s) throws RuleNotConfiguredException {
    super(s);
    configuredWildcards = MatcherWithWildcards.fromSettings(s, KEY);
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (rc.getActionRequest() instanceof SearchRequest) {
      // 1. Requesting none or all the indices means requesting allowed indices..
      if (rc.getIndices().size() == 0 || (rc.getIndices().contains("_all"))) {
        rc.setIndices(configuredWildcards.getMatchers());
        return MATCH;
      }

      // ----- Now you requested SOME indices, let's see if and what we can allow in..

      // 2. All indices match by wildcard?
      if (configuredWildcards.filter(rc.getIndices()).size() == rc.getIndices().size()) {
        return MATCH;
      }

      // 2.1 Detect non-wildcard requested indices that do not exist and return 404 (compatibility with vanilla ES)
      Set<String> real = rc.getAvailableIndicesAndAliases();
      for (final String idx : rc.getIndices()) {
        if (!idx.contains("*") && !real.contains(idx)) {
          Set<String> nonExistingIndex = new HashSet<>(1);
          nonExistingIndex.add(idx);
          rc.setIndices(nonExistingIndex);
          return MATCH;
        }
      }

      // 3. indices match by reverse-wildcard?
      // Expand requested indices to a subset of indices available in ES
      Set<String> expansion = new MatcherWithWildcards(rc.getIndices()).filter(rc.getAvailableIndicesAndAliases());

      // 4. Your request expands to no actual index, fine with me, it will return 404 on its own!
      if (expansion.size() == 0) {
        return MATCH;
      }

      // ------ Your request expands to many available indices, let's see which ones you are allowed to request..
      Set<String> allowedExpansion = configuredWildcards.filter(expansion);

      // 5. You requested some indices, but NONE were allowed
      if (allowedExpansion.size() == 0) {
        // #TODO should I set indices to rule wildcards?
        return NO_MATCH;
      }

      // 6. You requested some indices, I can allow you only SOME (we made sure the allowed set is not empty!).
      rc.setIndices(allowedExpansion);
      return MATCH;
    } else {

      // Handle <no-index>
      if (rc.getIndices().size() == 0 && configuredWildcards.getMatchers().contains("<no-index>")) {
        return MATCH;
      }

      // Reject if at least one requested index is not allowed by the rule conf
      for (String idx : rc.getIndices()) {
        if (!configuredWildcards.match(idx)) {
          return NO_MATCH;
        }
      }

      // Conditions are satisfied
      return MATCH;
    }

  }
}
