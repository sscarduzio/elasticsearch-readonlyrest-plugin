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
import tech.beshu.ror.unit.acl.blocks.rules.impl.ZeroKnowledgeMatchFilter;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Created by sscarduzio on 12/12/2016.
 */
public class ZeroKnowledgeMatchFilterTests {

  private Set<String> filter(Set<String> requested, Set<String> rules) {
    return ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(requested, new MatcherWithWildcards(rules));
  }

  @Test
  public void testAllToStar() {
    Set<String> filtered = filter(Sets.newHashSet("_all"), Sets.newHashSet("*"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("*"));
  }

  @Test
  public void testEmptyToStar() {
    Set<String> filtered = filter(Sets.newHashSet(), Sets.newHashSet("*"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("*"));
  }

  @Test
  public void testStarToStar() {
    Set<String> filtered = filter(Sets.newHashSet("*"), Sets.newHashSet("*"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("*"));
  }

  @Test
  public void testStarToPrecise() {
    Set<String> filtered = filter(Sets.newHashSet("*"), Sets.newHashSet("a"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("a"));
  }

  @Test
  public void testPreciseToStar() {
    Set<String> filtered = filter(Sets.newHashSet("a"), Sets.newHashSet("*"));
    // it means don't replace any input indices...
    assertNull(filtered);
  }

  @Test
  public void testPreciseToWC() {
    Set<String> filtered = filter(Sets.newHashSet("aa"), Sets.newHashSet("a*"));
    // it means don't replace any input indices...
    assertNull(filtered);
  }

  @Test
  public void testWcToPrecise() {
    Set<String> filtered = filter(Sets.newHashSet("a*"), Sets.newHashSet("aa"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("aa"));
  }

  @Test
  public void testWcToMorePreciseWC() {
    Set<String> filtered = filter(Sets.newHashSet("a*"), Sets.newHashSet("aa*"));
    assertEquals(1, filtered.size());
    assertTrue(filtered.contains("aa*"));
  }

  @Test
  public void testMorePreciseWcToWC() {
    Set<String> filtered = filter(Sets.newHashSet("aa*"), Sets.newHashSet("a*"));
    // it means don't replace any input indices...
    assertNull(filtered);
  }

}
