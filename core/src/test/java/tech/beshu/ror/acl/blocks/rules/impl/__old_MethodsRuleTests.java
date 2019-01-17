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

import com.google.common.collect.Sets;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.__old_MethodsRuleSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class __old_MethodsRuleTests {

  @Test
  public void testSimpleGET() {
    RuleExitResult res = match(Collections.singletonList(HttpMethod.GET), HttpMethod.GET);
    assertTrue(res.isMatch());
  }

  @Test
  public void testMultiple() {
    RuleExitResult res = match(Arrays.asList(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT), HttpMethod.GET);
    assertTrue(res.isMatch());
  }

  @Test
  public void testDeny() {
    RuleExitResult res = match(Arrays.asList(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT), HttpMethod.DELETE);
    assertFalse(res.isMatch());
  }

  private RuleExitResult match(List<HttpMethod> configured, HttpMethod found) {
    return match(configured, found, Mockito.mock(__old_RequestContext.class));
  }

  private RuleExitResult match(List<HttpMethod> configured, HttpMethod found, __old_RequestContext rc) {
    when(rc.getMethod()).thenReturn(found);
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = new __old_MethodsSyncRule(new __old_MethodsRuleSettings(Sets.newHashSet(configured)));
    return r.match(rc);
  }

}
