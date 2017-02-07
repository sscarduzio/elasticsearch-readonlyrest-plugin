/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.rules;

import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.Rule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsRule;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class MethodsRuleTests extends TestCase {

  private RuleExitResult match(List<String> configured, String found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, String found, RequestContext rc) throws RuleNotConfiguredException {
    when(rc.getMethod()).thenReturn(found);
    when(rc.isReadRequest()).thenReturn(true);

    Rule r = new MethodsRule(Settings.builder()
                               .putArray("methods", configured)
                               .build());

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }

  public void testSimpleGET() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("GET"), "GET");
    assertTrue(res.isMatch());
  }

  public void testMultiple() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("GET", "POST", "PUT"), "GET");
    assertTrue(res.isMatch());
  }

  public void testDeny() throws RuleNotConfiguredException {
    RuleExitResult res = match(Arrays.asList("GET", "POST", "PUT"), "DELETE");
    assertFalse(res.isMatch());
  }
}
