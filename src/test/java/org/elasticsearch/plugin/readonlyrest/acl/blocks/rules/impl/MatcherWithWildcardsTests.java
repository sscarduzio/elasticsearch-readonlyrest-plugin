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
import org.elasticsearch.plugin.readonlyrest.acl.domain.MatcherWithWildcards;
import org.junit.Test;

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
}
