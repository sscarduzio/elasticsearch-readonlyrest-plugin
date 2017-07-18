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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.mocks.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRuleSettings;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class IndicesRuleTests {

  @Test
  public void testSimpleIndex() {
    RuleExitResult res = match(singletonList("public-asd"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testSimpleWildcard() {
    RuleExitResult res = match(singletonList("public-*"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testReverseWildcard() {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.getAllIndicesAndAliases()).thenReturn(Sets.newHashSet(singletonList("public-asd")));

    RuleExitResult res = match(singletonList("public-asd"), singletonList("public-*"), rc);
    assertTrue(res.isMatch());
  }

  @Test
  public void testReturnAllowedSubset() {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.getAllIndicesAndAliases()).thenReturn(Sets.newHashSet(Lists.newArrayList("a", "b", "c")));

    RuleExitResult res = match(singletonList("a"), Lists.newArrayList("a", "b", "c"), rc);
    assertTrue(res.isMatch());
  }

  @Test
  public void test152() {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.isReadRequest()).thenReturn(true);
    when(rc.involvesIndices()).thenReturn(true);
    when(rc.getLoggedInUser()).thenReturn(Optional.empty());
    when(rc.getExpandedIndices()).thenReturn(Sets.newHashSet(singletonList("another_index")));
    when(rc.getAllIndicesAndAliases())
      .thenReturn(Sets.newHashSet(Lists.newArrayList("perfmon-bfarm", "another_index")));

    RuleExitResult res = match(
      // Mocks:  indices: ["perfmon*"]
      singletonList("perfmon*"),
      // The incoming request is directed to "another_index"
      singletonList("another_index"),
      rc
    );

    // Should be a NO_MATCH
    assertFalse(res.isMatch());
  }

  private RuleExitResult match(List<String> configured, List<String> found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, List<String> found, RequestContext rc) {
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = new IndicesSyncRule(
      IndicesRuleSettings.from(Sets.newHashSet(configured)),
      MockedESContext.INSTANCE
    );

    return r.match(rc);
  }

}
