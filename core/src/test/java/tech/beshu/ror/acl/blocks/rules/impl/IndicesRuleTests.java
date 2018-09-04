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

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.utils.MatcherWithWildcards;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.mocks.RequestContextMock;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.IndicesRuleSettings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.singletonList;
import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anySetOf;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class IndicesRuleTests {

  @Test
  public void test1() {
    MatcherWithWildcards matcher = new MatcherWithWildcards(Sets.newHashSet("a*"));

    Set<String> res = new ZeroKnowledgeIndexFilter(true).alterIndicesIfNecessary(Sets.newHashSet("*"), matcher);
    assertNotNull(res);
    assertTrue(res.contains("a*"));
    res = new ZeroKnowledgeIndexFilter(true).alterIndicesIfNecessary(Sets.newHashSet("b"), matcher);
    assertNotNull(res);
    assertTrue(res.isEmpty());

  }

  @Test
  public void test2() {
    Set<String> res = new ZeroKnowledgeIndexFilter(true).alterIndicesIfNecessary(Sets.newHashSet("a*"), new MatcherWithWildcards(Sets.newHashSet("a1*")));
    assertNotNull(res);
    assertTrue(res.contains("a1*"));
    assertFalse(res.contains("a*"));
  }

  @Test
  public void test3() {
    MatcherWithWildcards matcher = new MatcherWithWildcards(Sets.newHashSet("b:*", "a*"));
    Set<String> res = new ZeroKnowledgeIndexFilter(true).alterIndicesIfNecessary(Sets.newHashSet("*"), matcher);
    assertNotNull(res);
    assertTrue(res.contains("a*"));
    assertFalse(res.contains("b:*"));
  }

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

    RequestContext rc = RequestContextMock.mkSearchRequest(
      Sets.newHashSet("another_index"),
      Sets.newHashSet("perfmon-bfarm", "another_index"),
      Sets.newHashSet("another_index")
    );

    SyncRule r = new IndicesSyncRule(
      // Mocks:  indices: ["perfmon*"]
      IndicesRuleSettings.from(Sets.newHashSet(Sets.newHashSet("perfmon*"))),
      MockedESContext.INSTANCE
    );

    RuleExitResult res = r.match(rc);

    assertFalse(res.isMatch());
  }

  private RuleExitResult match(List<String> configured, List<String> found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, List<String> found, RequestContext rc) {
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.getAction()).thenReturn("indices:data/read/search");
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = new IndicesSyncRule(
        IndicesRuleSettings.from(Sets.newHashSet(configured)),
        MockedESContext.INSTANCE
    );

    return r.match(rc);
  }

}
