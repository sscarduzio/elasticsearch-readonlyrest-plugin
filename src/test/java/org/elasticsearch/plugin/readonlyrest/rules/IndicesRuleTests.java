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

package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class IndicesRuleTests extends TestCase {

  private RuleExitResult match(List<String> configured, List<String> found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, List<String> found, RequestContext rc)
      throws RuleNotConfiguredException {
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = IndicesSyncRule.fromSettings(
        Settings.builder()
            .putArray("indices", configured)
            .build(),
        MockedESContext.INSTANCE
    ).get();

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }


  public void testSimpleIndex() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("public-asd"), Arrays.asList("public-asd"));
    assertTrue(res.isMatch());
  }

  public void testSimpleWildcard() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("public-*"), Arrays.asList("public-asd"));
    assertTrue(res.isMatch());
  }

  public void testReverseWildcard() throws RuleNotConfiguredException {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.getAllIndicesAndAliases()).thenReturn(Sets.newHashSet(Arrays.asList("public-asd")));

    RuleExitResult res = match(Arrays.asList("public-asd"), Arrays.asList("public-*"), rc);
    assertTrue(res.isMatch());
  }

  public void testReturnAllowedSubset() throws RuleNotConfiguredException {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.getAllIndicesAndAliases()).thenReturn(Sets.newHashSet(Arrays.asList("a", "b", "c")));

    RuleExitResult res = match(Arrays.asList("a"), Arrays.asList("a", "b", "c"), rc);
    assertTrue(res.isMatch());
  }

  public void test152() throws RuleNotConfiguredException {
    RequestContext rc = Mockito.mock(RequestContext.class);
    when(rc.isReadRequest()).thenReturn(true);
    when(rc.involvesIndices()).thenReturn(true);
    when(rc.getLoggedInUser()).thenReturn(Optional.empty());
    when(rc.getExpandedIndices()).thenReturn(Sets.newHashSet(Arrays.asList("another_index")));
    when(rc.getAllIndicesAndAliases())
        .thenReturn(Sets.newHashSet(Arrays.asList("perfmon-bfarm", "another_index")));

    RuleExitResult res = match(
        // Mocks:  indices: ["perfmon*"]
        Arrays.asList("perfmon*"),

        // The incoming request is directed to "another_index"
        Arrays.asList("another_index"),

        rc
    );

    // Should be a NO_MATCH
    assertFalse(res.isMatch());
  }

}
