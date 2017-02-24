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

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

@RunWith(MockitoJUnitRunner.class)
public class IndicesRewriteRuleTests extends TestCase {

  @Captor
  private ArgumentCaptor<Set<String>> argumentCaptor;

  private RuleExitResult match(List<String> configured, List<String> found, List<String> expected) throws RuleNotConfiguredException {
    Collections.sort(found);
    Collections.sort(expected);
    RequestContext rc = Mockito.mock(RequestContext.class);
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    when(rc.getIndices()).thenReturn(foundSet);
    when(rc.involvesIndices()).thenReturn(true);
    when(rc.getExpandedIndices()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = new IndicesRewriteSyncRule(Settings.builder()
                                              .putArray("indices_rewrite", configured)
                                              .build());
    RuleExitResult res = r.match(rc);
    rc.commit();
    verify(rc).setIndices(argumentCaptor.capture());

    String expectedJ = Joiner.on(",").join(expected);
    String capturedJ = Joiner.on(",").join(argumentCaptor.getValue());
    assertEquals(expectedJ, capturedJ);
    return res;
  }

  @Test
  public void testSimpleIndex() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("public-asd", "replacement"), Arrays.asList("public-asd"), Arrays.asList("replacement"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testRegexIndex() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("public-.*", "replacement"), Arrays.asList("public-asd"), Arrays.asList("replacement"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testBigRegexIndex() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("^public-.*$", "replacement"), Arrays.asList("public-asd"), Arrays.asList("replacement"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testBigRegexIndexMultiIndex() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("^public-.*$", "replacement"), Arrays.asList("public-asd", "quack"), Arrays.asList("replacement", "quack"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRule() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("^public-.*$", ".*ack", "replacement"), Arrays.asList("public-asd", "quack", "ack"), Arrays.asList("replacement"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testBigRegexIndexMultiIndexMultiRuleWithOutlier() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("^public-.*$", ".*ack", "replacement"), Arrays.asList("public-asd", "quack", "ack", "outlier"), Arrays.asList("replacement", "outlier"));
    assertTrue(res.isMatch());
  }

}
