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
package tech.beshu.ror.utils;

import com.google.common.collect.Sets;
import scala.jdk.CollectionConverters;
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity;
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher;
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher$;
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable$;

import java.util.Set;

public class StringPatternsMatcherJava {

  private final PatternsMatcher<String> underlyingMatcher;

  public StringPatternsMatcherJava(Iterable<String> patterns, CaseSensitivity caseSensitivity) {
    this.underlyingMatcher = PatternsMatcher$.MODULE$.create(
        CollectionConverters.IterableHasAsScala(patterns).asScala(),
        Matchable$.MODULE$.matchable(s -> s, caseSensitivity)
    );
  }

  public StringPatternsMatcherJava(PatternsMatcher<?> matcher) {
    this.underlyingMatcher = PatternsMatcher$.MODULE$.create(
        matcher.patterns(),
        Matchable$.MODULE$.matchable(s -> s, matcher.caseSensitivity())
    );
  }

  public Set<String> getPatterns() {
    return Sets.newHashSet(
        CollectionConverters.IterableHasAsJava(underlyingMatcher.patterns()).asJava()
    );
  }

  public CaseSensitivity getCaseSensitivity() {
    return underlyingMatcher.caseSensitivity();
  }

  public boolean match(String haystack) {
    return underlyingMatcher.match(haystack);
  }

  public Set<String> filter(Set<String> haystack) {
    return CollectionConverters
        .SetHasAsJava(
            underlyingMatcher
                .filter(
                  CollectionConverters
                      .SetHasAsScala(haystack)
                      .asScala()
                )
        )
        .asJava();
  }
}
