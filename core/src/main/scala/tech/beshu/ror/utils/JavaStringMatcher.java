package tech.beshu.ror.utils;

import com.google.common.collect.Sets;
import scala.jdk.CollectionConverters;
import tech.beshu.ror.accesscontrol.domain.GlobPattern;
import tech.beshu.ror.accesscontrol.domain.GlobPattern$;
import tech.beshu.ror.accesscontrol.matchers.Matcher;

import java.util.Set;

public class JavaStringMatcher {

  private final Matcher<String> underlyingMatcher;

  public JavaStringMatcher(Iterable<String> patterns) {
    Matchable<String> matchable = Matchable$.MODULE$.matchable(s -> s, GlobPattern$.MODULE$.caseSensitivity().enabled()); // todo: really?
    this.underlyingMatcher = MatcherWithWildcardsScala$.MODULE$.create(
        CollectionConverters.IterableHasAsScala(patterns).asScala(), matchable
    );
  }

  public JavaStringMatcher(Matcher<?> matcher) {
    Matchable<String> matchable = Matchable$.MODULE$.matchable(s -> s, GlobPattern$.MODULE$.caseSensitivity().enabled()); // todo: really?
    this.underlyingMatcher = MatcherWithWildcardsScala$.MODULE$.<String>create(
        matcher.globPatterns().map(GlobPattern::pattern),
        matchable
    );
  }

  public Set<String> getMatchers() {
    return Sets.newHashSet(
        CollectionConverters.IterableHasAsJava(underlyingMatcher.globPatterns().map(GlobPattern::pattern)).asJava()
    );
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
