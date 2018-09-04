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
import org.junit.Test;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;

import java.util.Set;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by sscarduzio on 12/12/2016.
 */
public class MatcherWithWildcardsTests {

  @Test
  public void testMatchSimpleString() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("a"));
  }

  @Test
  public void testMatchSimpleString2() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("b"));
  }

  @Test
  public void testMatchStarPatternRight() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("bxxx")); // INVERTED (should be true)!!!
  }

  @Test
  public void testMatchStarPatternLeft() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c"));
    assertTrue(m.match("xxxc"));
  }

  @Test
  public void testNoMatchStarPatternRight() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("c*"));
    assertFalse(m.match("xxxcxxx"));
  }

  @Test
  public void testAlessandro() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("streams_dev-*"));
    assertTrue(m.match("streams_dev-rfh"));
  }

  @Test
  public void testNoMatchStarPatternLeft() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c"));
    assertFalse(m.match("xxxcxxx"));
  }

  @Test
  public void testMatchStarPatternBilateral() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c*"));
    assertTrue(m.match("xxxcxxx"));
  }

  @Test
  public void testMatchDotKibana() {
    MatcherWithWildcards m = new MatcherWithWildcards(
        Sets.newHashSet("<no-index>", ".kibana", ".kibana-devnull", "logstash-*", "default")
    );
    assertTrue(m.match(".kibana"));
  }

  @Test
  public void testZeroCharGlob() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("perf*mon_my_test*"));
    assertTrue(m.match("perfmon_my_test1"));
  }

  @Test
  public void testMatchingMatchers() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a*", "b*"));
    Set<String> matchedMatchers = m.matchingMatchers(Sets.newHashSet("asd"));
    assertTrue(matchedMatchers.contains("a*"));
    assertFalse(matchedMatchers.contains("b*"));
    assertTrue(matchedMatchers.size() == 1);
  }

  @Test
  public void testMatchingMatchersMulti() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a*", "b*", "z"));
    Set<String> matchedMatchers = m.matchingMatchers(Sets.newHashSet("asd", "bsd", "xxx"));
    assertTrue(matchedMatchers.contains("a*"));
    assertTrue(matchedMatchers.contains("b*"));
    assertTrue(matchedMatchers.size() == 2);
  }

}
