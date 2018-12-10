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
import tech.beshu.ror.commons.domain.Value;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.LocalHostsRuleSettings;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class LocalHostsRuleTests {

  private RuleExitResult match(String configured, String found) {
    return match(configured, found, Mockito.mock(__old_RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, __old_RequestContext rc) {
    when(rc.getLocalAddress()).thenReturn(found);

    Set<Value<String>> configV = Sets.newHashSet(configured).stream().map(i -> Value.fromString(i, Function.identity())).collect(Collectors.toSet());
    LocalHostsRuleSettings hrset = new LocalHostsRuleSettings(configV);
    SyncRule r = new LocalHostsSyncRule(hrset, new MockedESContext());
    return r.match(rc);
  }

  @Test
  public void testOKip() {
    RuleExitResult res = match("1.1.1.1", "1.1.1.1");
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
}
