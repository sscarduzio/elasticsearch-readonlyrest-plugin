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

import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Lists;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.XForwardedForRuleSettings;

import java.util.HashMap;
import java.util.Map;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class XForwardedForSyncRuleTests {

  private RuleExitResult match(String configured, String found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) {
    Map<String, String> headers = new HashMap<>(1);
    headers.put("X-Forwarded-For", found);
    when(rc.getHeaders()).thenReturn(headers);

    XForwardedForRuleSettings hrset = XForwardedForRuleSettings.from(Lists.newArrayList(configured));
    SyncRule r = new XForwardedForSyncRule(hrset, MockedESContext.INSTANCE);
    return r.match(rc);
  }

  @Test
  public void testOKip() {
    RuleExitResult res = match("1.1.1.1", "1.1.1.1");
    assertTrue(res.isMatch());
  }

  @Test
  public void testOKnet() {
    RuleExitResult res = match("1.1.0.0/16", "1.1.1.2");
    assertTrue(res.isMatch());
  }

  @Test
  public void testOKName() {
    RuleExitResult res = match("google.com", "google.com");
    assertTrue(res.isMatch());
  }

  @Test
  public void testKOUnresolved() {
    RuleExitResult res = match("cannotresolve.lolol", "x");
    assertFalse(res.isMatch());
  }

  @Test
  public void testKO() {
    RuleExitResult res = match("google.com", "x");
    assertFalse(res.isMatch());
  }

  @Test
  public void testLocal() {
    RuleExitResult res = match("localhost", "127.0.0.1");
    assertTrue(res.isMatch());
  }

  @Test
  public void testLocalReverse() {
    RuleExitResult res = match("127.0.0.1", "localhost");
    assertTrue(res.isMatch());
  }
}
