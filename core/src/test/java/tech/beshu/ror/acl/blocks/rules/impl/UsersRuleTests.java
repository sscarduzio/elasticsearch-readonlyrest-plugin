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

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.requestcontext.__old_RequestContext;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 04/09/2018.
 */

public class UsersRuleTests {

  @Test
  public void testSimpleMatch() {
    RuleExitResult res = match(Lists.newArrayList("asd"), "asd");
    assertTrue(res.isMatch());
  }

  @Test
  public void testMismatch() {
    RuleExitResult res = match(Lists.newArrayList("_asd"), "asd");
    assertFalse(res.isMatch());
  }

  @Test
  public void testWCmatch() {
    RuleExitResult res = match(Lists.newArrayList("as*"), "asd");
    assertTrue(res.isMatch());
  }

  @Test
  public void testWCMismatch() {
    RuleExitResult res = match(Lists.newArrayList("as*"), "aXsd");
    assertFalse(res.isMatch());
  }

  private RuleExitResult match(List<String> configured, String found) {
    return match(configured, found, Mockito.mock(__old_RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, String found, __old_RequestContext rc) {
    when(rc.getLoggedInUser()).thenReturn(Optional.ofNullable(Strings.isNullOrEmpty(found) ? null : new LoggedUser(found)));

    SyncRule r = new UsersSyncRule(new UsersSyncRule.Settings(Sets.newHashSet(configured)));
    return r.match(rc);
  }

}
