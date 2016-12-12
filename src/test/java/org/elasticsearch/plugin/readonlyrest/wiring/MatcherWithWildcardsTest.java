package org.elasticsearch.plugin.readonlyrest.wiring;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Created by sscarduzio on 12/12/2016.
 */
public class MatcherWithWildcardsTest {

  @org.junit.Test
  public void matchSimpleString() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("a"));
  }

  @org.junit.Test
  public void matchStarPatternRight() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("a", "b*"));
    assertTrue(m.match("bxxx"));
  }

  @org.junit.Test
  public void matchStarPatternLeft() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c"));
    assertTrue(m.match("xxxc"));
  }

  @org.junit.Test
  public void noMatchStarPatternRight() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("c*"));
    assertFalse(m.match("xxxcxxx"));
  }

  @org.junit.Test
  public void noMatchStarPatternLeft() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c"));
    assertFalse(m.match("xxxcxxx"));
  }

  @org.junit.Test
  public void matchStarPatternBilateral() {
    MatcherWithWildcards m = new MatcherWithWildcards(Sets.newHashSet("*c*"));
    assertTrue(m.match("xxxcxxx"));
  }
}
