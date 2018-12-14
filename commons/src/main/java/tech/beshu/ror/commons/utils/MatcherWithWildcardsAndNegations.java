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

package tech.beshu.ror.commons.utils;

import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Sets;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MatcherWithWildcardsAndNegations {

  private MatcherWithWildcards positivePatterns;
  private MatcherWithWildcards negativePatterns;

  public MatcherWithWildcardsAndNegations(Set<String> patterns) {
    Map<Boolean, List<String>> fieldLists = patterns.stream().collect(Collectors.partitioningBy(f -> f.startsWith("~")));

    MatcherWithWildcards m;

    m = new MatcherWithWildcards(Sets.newHashSet(fieldLists.get(false)));
    this.positivePatterns = m.getMatchers().isEmpty() ? null : m;

    m = new MatcherWithWildcards(Sets.newHashSet(fieldLists.get(true).stream().map(x -> x.substring(1, x.length())).collect(Collectors.toSet())));
    this.negativePatterns = m.getMatchers().isEmpty() ? null : m;
  }

  public boolean match(String fieldName) {
    // If we have some negative matchers, and the field matches them, we bar it.
    if (this.negativePatterns != null && this.negativePatterns.match(fieldName)) {
      return false;
    }

    // If positive matchers exist and the field does not match them, we bar it.
    if (this.positivePatterns != null && !this.positivePatterns.match(fieldName)) {
      return false;
    }

    return true;
  }

  public Set<String> filter(Set<String> haystack) {
    if (haystack == null) {
      return Collections.emptySet();
    }
    Set<String> filtered = Sets.newHashSet();
    for (String hs : haystack) {
      if (match(hs)) {
        filtered.add(hs);
      }
    }
    return filtered;
  }
}
