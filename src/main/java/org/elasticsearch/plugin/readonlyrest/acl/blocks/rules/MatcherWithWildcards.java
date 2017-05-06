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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.VariablesManager;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by sscarduzio on 02/04/2016.
 */
public class MatcherWithWildcards {

  private static Set<String> empty = new HashSet<>(0);
  private final Set<String> allMatchers = Sets.newHashSet();
  private final Set<Pattern> wildcardMatchers = Sets.newHashSet();

  public MatcherWithWildcards(Set<String> matchers) {
    for (String a : matchers) {
      a = normalizePlusAndMinusIndex(a);
      if (Strings.isNullOrEmpty(a)) {
        continue;
      }
      if (a.contains("*")) {
        // Patch the simple star wildcard to become a regex: ("*" -> ".*")
        String regex = "^" + ("\\Q" + a + "\\E").replace("*", "\\E.*\\Q") + "$";

        // Pre-compile the regex pattern matcher to validate the regex
        // AND faster matching later on.
        wildcardMatchers.add(Pattern.compile(regex));

        // Let's match this also literally
        allMatchers.add(a);
      }
      else {
        // A plain word can be matched as string
        allMatchers.add(a.trim());
      }
    }
  }

  /**
   * Returns null if the matchable is not worth processing because it's invalid or starts with "-"
   */
  private String normalizePlusAndMinusIndex(String s) {
    if (Strings.isNullOrEmpty(s)) {
      return null;
    }
    // Ignore the excluded indices
    if (s.startsWith("-")) {
      return null;
    }
    // Call included indices with their name
    if (s.startsWith("+")) {
      if (s.length() == 1) {
        return null;
      }
      return s.substring(1, s.length());
    }
    return s;
  }

  public Set<String> getMatchers() {
    return allMatchers;
  }

  public String matchWithResult(String matchable) {

    matchable = normalizePlusAndMinusIndex(matchable);

    if (matchable == null) {
      return null;
    }

    // Try to match plain strings first
    if (allMatchers.contains(matchable)) {
      return matchable;
    }

    for (Pattern p : wildcardMatchers) {
      Matcher m = p.matcher(matchable);
      if (m == null) {
        continue;
      }
      if (m.find()) {
        return matchable;
      }
    }

    return null;
  }

  public boolean match(String s) {
    return matchWithResult(s) != null;
  }

  public Set<String> filter(Set<String> haystack) {
    if (haystack.isEmpty()) return empty;
    Set<String> res = Sets.newHashSet();
    for (String s : haystack) {
      if (match(s)) {
        res.add(s);
      }
    }
    return res;
  }

}
