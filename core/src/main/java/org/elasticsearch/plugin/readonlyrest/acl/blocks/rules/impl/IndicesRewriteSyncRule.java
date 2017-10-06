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

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.domain.Value;
import org.elasticsearch.plugin.readonlyrest.requestcontext.IndicesRequestContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRewriteRuleSettings;

import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesRewriteSyncRule extends SyncRule {

  private final LoggerShim logger;
  private final Set<Pattern> targetPatterns;
  private final Value<String> replacement;
  private final IndicesRewriteRuleSettings settings;

  public IndicesRewriteSyncRule(IndicesRewriteRuleSettings s, ESContext context) {
    this.logger = context.logger(getClass());
    this.replacement = s.getReplacement();
    this.targetPatterns = s.getTargetPatterns();
    this.settings = s;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    if (!rc.involvesIndices()) {
      return MATCH;
    }

    final Optional<String> theReplacementOpt = this.replacement.getValue(rc);
    if (theReplacementOpt.isPresent()) {
      final String theReplacement = theReplacementOpt.get();
      if (rc.hasSubRequests()) {
        rc.scanSubRequests((src) -> {
          rewrite(src, theReplacement);
          return Optional.of(src);
        });
      }
      else {
        rewrite(rc, theReplacement);
      }

      // This is a side-effect only rule, will always match
      return MATCH;
    }
    else {
      return NO_MATCH;
    }
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

  private void rewrite(IndicesRequestContext rc, String currentReplacement) {
    // Expanded indices
    final Set<String> oldIndices = Sets.newHashSet(rc.getIndices());

    if (rc.isReadRequest()) {
      // Translate all the wildcards
      oldIndices.clear();
      oldIndices.addAll(rc.getExpandedIndices(rc.getIndices()));

      // If asked for non-existent indices, let's show them to the rewriter
      Set<String> available = rc.getAllIndicesAndAliases();
      rc.getIndices().stream()
        .filter(i -> !available.contains(i))
        .forEach(oldIndices::add);
    }

    Set<String> newIndices = Sets.newHashSet();

    for (Pattern p : targetPatterns) {
      Iterator<String> it = oldIndices.iterator();
      while (it.hasNext()) {
        String i = it.next();
        String maybeChanged = p.matcher(i).replaceFirst(currentReplacement);
        if (!i.equals(maybeChanged)) {
          newIndices.add(maybeChanged);
          it.remove();
        }
      }
    }
    oldIndices.addAll(newIndices);

    if (oldIndices.isEmpty()) {
      oldIndices.add("*");
    }

    rc.setIndices(oldIndices);
  }

}