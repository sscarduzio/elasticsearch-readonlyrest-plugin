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
import tech.beshu.ror.commons.utils.MatcherWithWildcardsAndNegations;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by sscarduzio on 12/12/2016.
 */
public class MatcherWithWildcardsAndNegationsTests {

  @Test
  public void testMatchSimpleStringNegSimple() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "~a"));
    assertFalse(m.match("a"));
  }
  @Test
  public void testMatchSimpleStringNegWc() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "~a*"));
    assertFalse(m.match("a"));
  }
  @Test
  public void testMatchSimpleStringNegPos() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "a","~b"));
    assertTrue(m.match("a"));
  }
  @Test
  public void testMatchSimpleStringNegWcPosWc() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "a*", "~b*"));
    assertTrue(m.match("a"));
  }

  @Test
  public void testMatchSimulation1() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("dummy", "_id", "~du*2"));
    assertTrue(m.match("dummy"));
  }

  @Test
  public void testMatchSimulation2() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("dummy", "_id", "~du*2"));
    assertTrue(m.match("dummy"));
  }

  @Test
  public void testMatchSimulation3() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("_id", "du*", "~du*2"));
    assertTrue(m.match("_id"));
    assertTrue(m.match("dummy"));
    assertFalse(m.match("dummy2"));
  }

  @Test
  public void testMatchSimpleString() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("a"));
  }

  @Test
  public void testMatchStarPatternRight() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("bxxx")); // INVERTED (should be true)!!!
  }

  @Test
  public void testMatchStarPatternLeft() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("*c"));
    assertTrue(m.match("xxxc"));
  }

  @Test
  public void testNoMatchStarPatternRight() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("c*"));
    assertFalse(m.match("xxxcxxx"));
  }

  @Test
  public void testAlessandro() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("streams_dev-*"));
    assertTrue(m.match("streams_dev-rfh"));
  }

  @Test
  public void testNoMatchStarPatternLeft() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("*c"));
    assertFalse(m.match("xxxcxxx"));
  }

  @Test
  public void testMatchStarPatternBilateral() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("*c*"));
    assertTrue(m.match("xxxcxxx"));
  }

  @Test
  public void testMatchDotKibana() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(
        Sets.newHashSet("<no-index>", ".kibana", ".kibana-devnull", "logstash-*", "default")
    );
    assertTrue(m.match(".kibana"));
  }

  @Test
  public void testZeroCharGlob() {
    MatcherWithWildcardsAndNegations m = new MatcherWithWildcardsAndNegations(Sets.newHashSet("perf*mon_my_test*"));
    assertTrue(m.match("perfmon_my_test1"));
  }
}
