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
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalSearchHit;

import java.io.IOException;
import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 20/02/2016.
 */
public class IndicesRewriteSyncRule extends SyncRule {

  private final Logger logger = Loggers.getLogger(this.getClass());

  private final Pattern[] targetPatterns;
  private final String replacement;


  public IndicesRewriteSyncRule(Settings s) throws RuleNotConfiguredException {
    super();
    // Will work fine also with single strings (non array) values.
    String[] a = s.getAsArray(getKey());

    if (a == null || a.length == 0) {
      throw new RuleNotConfiguredException();
    }

    if (a.length < 2) {
      logger.error("Minimum two arguments required for " + getKey() + ". I.e. [target1, target2, replacement]");
      throw new RuleNotConfiguredException();
    }

    String[] targets = new String[a.length - 1];
    System.arraycopy(a, 0, targets, 0, a.length - 1);
    replacement = a[a.length - 1];

    targetPatterns = Arrays.stream(targets)
      .distinct()
      .filter(Objects::nonNull)
      .filter(Strings::isNotBlank)
      .map(str -> Pattern.compile(str))
      .toArray(Pattern[]::new);
  }

  public Pattern[] getTargetPatterns() {
    return targetPatterns;
  }

  public String getReplacement() {
    return replacement;
  }

  @Override
  public RuleExitResult match(RequestContext rc) {

    if (!rc.involvesIndices()) {
      return MATCH;
    }

    // Expanded indices
    final Set<String> oldIndices = Sets.newHashSet(rc.getIndices());

    if (rc.isReadRequest()) {

      // Translate all the wildcards
      oldIndices.clear();
      oldIndices.addAll(rc.getExpandedIndices());

      // If asked for non-existent indices, let's show them to the rewriter
      Set<String> available = rc.getAvailableIndicesAndAliases();
      rc.getIndices().stream()
        .filter(i -> !available.contains(i))
        .forEach(i -> oldIndices.add(i));
    }

    Set<String> newIndices = Sets.newHashSet();

    String currentReplacement = replacement;

    String currentUser = rc.getLoggedInUser();
    if (!Strings.isEmpty(currentUser)) {
      currentReplacement = replacement.replaceAll("@user", rc.getLoggedInUser());
    }

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

    // This is a side-effect only rule, will always match
    return MATCH;
  }


  @Override
  public boolean onResponse(RequestContext rc, ActionRequest ar, ActionResponse response) {
    if (response instanceof SearchResponse) {

      // Translate the search results indices
      SearchResponse sr = (SearchResponse) response;
      String serResp = sr.toString();
      logger.info(serResp);
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

        Arrays.stream(sr.getHits().getHits()).forEach(h -> {
          try {
            Field f = InternalSearchHit.class.getDeclaredField("shard");
            f.setAccessible(true);

            SearchShardTarget sst = (SearchShardTarget) f.get(h);
            f = SearchShardTarget.class.getDeclaredField("index");
            f.setAccessible(true);
            f.set(sst, new Text(rc.getOriginalIndices().iterator().next()));
          } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
          }
        });

        return null;
      });
    }
    return true;
  }

  private String convertToJson(BytesReference searchSource) {
    if (searchSource != null)
      try {
        return XContentHelper.convertToJson(searchSource, true);
      } catch (IOException e) {
        logger.warn("Unable to convert searchSource to JSON", e);
      }
    return "";
  }

}
