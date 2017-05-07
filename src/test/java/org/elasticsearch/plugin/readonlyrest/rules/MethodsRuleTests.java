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
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MethodsRuleSettings;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod.GET;
import static org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod.POST;
import static org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod.PUT;
import static org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod.DELETE;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class MethodsRuleTests extends TestCase {

  private RuleExitResult match(List<HttpMethod> configured, HttpMethod found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<HttpMethod> configured, HttpMethod found, RequestContext rc) {
    when(rc.getMethod()).thenReturn(found);
    when(rc.isReadRequest()).thenReturn(true);

    SyncRule r = new MethodsSyncRule(new MethodsRuleSettings(Sets.newHashSet(configured)));
    return r.match(rc);
  }

  public void testSimpleGET() {
    RuleExitResult res = match(Arrays.asList(GET), GET);
    assertTrue(res.isMatch());
  }

  public void testMultiple() {
    RuleExitResult res = match(Arrays.asList(GET, POST, PUT), GET);
    assertTrue(res.isMatch());
  }

  public void testDeny() {
    RuleExitResult res = match(Arrays.asList(GET, POST, PUT), DELETE);
    assertFalse(res.isMatch());
  }
}
