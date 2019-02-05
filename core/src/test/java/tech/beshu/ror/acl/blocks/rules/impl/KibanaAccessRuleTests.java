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
import junit.framework.TestCase;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.commons.domain.KibanaAccess;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.KibanaAccessRuleSettings;

import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */
public class KibanaAccessRuleTests {

  @Test
  public void testCLUSTER() {
    for (String action : KibanaAccessSyncRule.CLUSTER.getMatchers()) {
      System.out.println("trying " + action + " as RO");
      assertTrue(matchRule(KibanaAccess.RO, action).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule(KibanaAccess.RW, action).isMatch());
    }
  }

  @Test
  public void testRO() {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      System.out.println("trying " + action + " as RO_STRICT");
      assertTrue(matchRule(KibanaAccess.RO_STRICT, action).isMatch());
      System.out.println("trying " + action + " as RO");
      assertTrue(matchRule(KibanaAccess.RO, action).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule(KibanaAccess.RW, action).isMatch());
    }
  }

  @Test
  public void testRW() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      String a = action.replace("*", "_");

      System.out.println("trying " + a + " as RO_STRICT");
      TestCase.assertFalse(
        matchRule(KibanaAccess.RO_STRICT, a, Sets.newHashSet(".kibana"), ".kibana", true)
          .isMatch()
      );

      System.out.println("trying " + a + " as RO");
      TestCase.assertFalse(
        matchRule(KibanaAccess.RO, a, Sets.newHashSet(".kibana"), ".kibana", true)
          .isMatch()
      );

      System.out.println("trying " + a + " as RW");
      assertTrue(
        matchRule(KibanaAccess.RW, a, Sets.newHashSet(".kibana"), ".kibana", true)
          .isMatch()
      );
    }
  }

  @Test
  public void testROotherIndices() {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx")).isMatch());
      assertTrue(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx")).isMatch());
      assertTrue(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx")).isMatch());
    }
  }

  @Test
  public void testRWotherIndices() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx")).isMatch());
      TestCase.assertFalse(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx")).isMatch());
      TestCase.assertFalse(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx")).isMatch());
    }
  }

  @Test
  public void testROmixedIndices() {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertTrue(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertTrue(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  @Test
  public void testRWmixedIndices() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      TestCase.assertFalse(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      TestCase.assertFalse(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  @Test
  public void testRWcustomKibanaIndex() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);

    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      System.out.println("trying " + action + " as RO_STRICT");
      TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true).isMatch());

      System.out.println("trying " + action + " as RO");
      TestCase.assertFalse(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true).isMatch());

      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true).isMatch());
    }
  }

  @Test
  public void testRONonStrictOperations() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);
    String action = "indices:data/write/index";
    String uri = "/" + customKibanaIndex + "/index-pattern/job";

    System.out.println("trying " + action + " as RO");
    TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true, uri).isMatch());
    TestCase.assertTrue(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true, uri).isMatch());
  }

  @Test
  public void testRONonStrictOperations2() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);
    String action = "indices:data/write/delete";
    String uri = "/" + customKibanaIndex + "/index-pattern/nilb-auh-filebeat-*";

    System.out.println("trying " + action + " as RO");
    TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true, uri).isMatch());
    TestCase.assertTrue(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true, uri).isMatch());
  }

  @Test
  public void testRONonStrictOperations3() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);
    String action = "indices:admin/template/put";
    String uri = "/_template/kibana_index_template%3A.kibana";
    System.out.println("trying " + action + " as RO");
    TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true, uri).isMatch());
    TestCase.assertTrue(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true, uri).isMatch());
  }

  @Test
  public void testRONonStrictOperations4() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);
    String action = "indices:data/write/update";
    String uri = "/" + customKibanaIndex + "/doc/index-pattern%3A895e56e0-d873-11e8-bd16-3dcc5288c87b/_update?";
    System.out.println("trying " + action + " as RO");
    TestCase.assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true, uri).isMatch());
    TestCase.assertTrue(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true, uri).isMatch());
  }


  private RuleExitResult match(Conf configured, Found found, RequestContext rc, boolean involvesIndices) {
    when(rc.involvesIndices()).thenReturn(involvesIndices);
    when(rc.getIndices()).thenReturn(found.indices);
    when(rc.getAction()).thenReturn(found.action);
    when(rc.getUri()).thenReturn(found.uri);
    when(rc.resolveVariable(anyString())).then(invocation -> Optional.of((String) invocation.getArguments()[0]));

    SyncRule r = new KibanaAccessSyncRule(
      new KibanaAccessRuleSettings(configured.accessLevel, configured.kibanaIndex),
      MockedESContext.INSTANCE
    );
    return r.match(rc);
  }

  private RuleExitResult matchRule(KibanaAccess accessLevel, String action) {
    return matchRule(accessLevel, action, Sets.newHashSet(".kibana"));
  }

  private RuleExitResult matchRule(KibanaAccess accessLevel, String action, Set<String> indices) {
    return matchRule(accessLevel, action, indices, ".kibana", false);
  }

  private RuleExitResult matchRule(KibanaAccess accessLevel, String action, Set<String> indices,
                                   String kibanaIndex, boolean involvesIndices) {
    return matchRule(accessLevel, action, indices, kibanaIndex, involvesIndices, "");
  }

  private RuleExitResult matchRule(KibanaAccess accessLevel, String action,
                                   Set<String> indices, String kibanaIndex, boolean involvesIndices, String uri) {
    Conf conf = new Conf();
    conf.accessLevel = accessLevel;
    conf.kibanaIndex = kibanaIndex;

    Found found = new Found();
    found.uri = uri;
    found.action = action;
    found.indices = indices;

    return match(conf, found, Mockito.mock(RequestContext.class), involvesIndices);
  }

  class Conf {
    KibanaAccess accessLevel = KibanaAccess.RO;
    String kibanaIndex = ".kibana";
  }

  class Found {
    String action = null;
    Set<String> indices = Sets.newHashSet(".kibana");
    String uri = "";
  }
}