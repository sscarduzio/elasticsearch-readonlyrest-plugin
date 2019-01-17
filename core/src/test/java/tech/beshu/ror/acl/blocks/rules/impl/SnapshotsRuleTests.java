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
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.__old_RequestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 17/05/2018.
 */

public class SnapshotsRuleTests {

  @Test
  public void testREADSimpleSnapshot() {
    RuleExitResult res = matchAsRead(singletonList("public-asd"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testREADSimpleWildcard() {
    RuleExitResult res = matchAsRead(singletonList("public-*"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testWRITESimpleWildcard() {
    RuleExitResult res = matchAsWrite(singletonList("public-*"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testWRITESimpleWildcardNoMatch() {
    RuleExitResult res = matchAsWrite(singletonList("public-*"), singletonList("x_public-asd"));
    assertFalse(res.isMatch());
  }

  @Test
  public void testREADMulti() {
    RuleExitResult res = matchAsRead(Lists.newArrayList("public-*", "n"), Lists.newArrayList("public-asd", "q"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testWRITEMulti() {
    RuleExitResult res = matchAsWrite(Lists.newArrayList("public-*", "n"), Lists.newArrayList("public-asd", "q"));
    assertFalse(res.isMatch());
  }

  private RuleExitResult matchAsRead(List<String> configured, List<String> found) {
    __old_RequestContext rc = Mockito.mock(__old_RequestContext.class);
    when(rc.getSnapshots()).thenReturn(Sets.newHashSet(found));
    when(rc.isReadRequest()).thenReturn(true);

    when(rc.getAction()).thenReturn("cluster:admin/snapshot/get");
    return match(configured, found, rc);
  }

  @Test
  public void testSimpleWildcardForbid() {
    RuleExitResult res = matchAsRead(singletonList("x-*"), singletonList("public-asd"));
    assertFalse(res.isMatch());
  }

  @Test
  public void testSimpleWildcardForbidW() {
    RuleExitResult res = matchAsWrite(singletonList("x-*"), singletonList("public-asd"));
    assertFalse(res.isMatch());
  }

  private RuleExitResult matchAsWrite(List<String> configured, List<String> found) {
    __old_RequestContext rc = Mockito.mock(__old_RequestContext.class);
    when(rc.getSnapshots()).thenReturn(Sets.newHashSet(found));
    when(rc.isReadRequest()).thenReturn(false);

    when(rc.getAction()).thenReturn("cluster:admin/snapshot/get");
    return match(configured, found, rc);
  }

  private RuleExitResult match(List<String> configured, List<String> found, __old_RequestContext rc) {
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    Map<String, Object> yamlMap = new HashMap() {{
      put("snapshots", configured);
    }};
    SyncRule r = new SnapshotsSyncRule(SnapshotsSyncRule.Settings.fromBlockSettings(new RawSettings(yamlMap, LoggerShim.dummy())), MockedESContext.INSTANCE);
    return r.match(rc);
  }

}
