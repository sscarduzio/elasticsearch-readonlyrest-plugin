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
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 17/05/2018.
 */

public class RepositoriesRuleTests {

  @Test
  public void testSimpleRepository() {
    RuleExitResult res = match(singletonList("public-asd"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  @Test
  public void testSimpleWildcard() {
    RuleExitResult res = match(singletonList("public-*"), singletonList("public-asd"));
    assertTrue(res.isMatch());
  }

  private RuleExitResult match(List<String> configured, List<String> found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(List<String> configured, List<String> found, RequestContext rc) {
    Set<String> foundSet = Sets.newHashSet();
    foundSet.addAll(found);
    when(rc.getRepositories()).thenReturn(foundSet);
    when(rc.isReadRequest()).thenReturn(true);

    Map<String, Object> yamlMap = new HashMap() {{
      put("repositories", configured);
    }};
    SyncRule r = new RepositoriesSyncRule(RepositoriesSyncRule.Settings.fromBlockSettings(new RawSettings(yamlMap, LoggerShim.dummy())), MockedESContext.INSTANCE);
    return r.match(rc);
  }

}
