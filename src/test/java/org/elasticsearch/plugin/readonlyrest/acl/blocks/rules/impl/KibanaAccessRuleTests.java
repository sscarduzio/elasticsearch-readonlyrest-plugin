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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.domain.KibanaAccess;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaAccessRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */
public class KibanaAccessRuleTests extends TestCase {

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

  public void testCLUSTER() {
    for (String action : KibanaAccessSyncRule.CLUSTER.getMatchers()) {
      System.out.println("trying " + action + " as RO");
      assertTrue(matchRule(KibanaAccess.RO, action).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule(KibanaAccess.RW, action).isMatch());
    }
  }

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

  public void testRW() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      String a = action.replace("*", "_");

      System.out.println("trying " + a + " as RO_STRICT");
      assertFalse(
          matchRule(KibanaAccess.RO_STRICT, a, Sets.newHashSet(".kibana"), ".kibana", true)
              .isMatch()
      );

      System.out.println("trying " + a + " as RO");
      assertFalse(
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

  public void testROotherIndices() {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx")).isMatch());
      assertTrue(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx")).isMatch());
      assertTrue(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx")).isMatch());
    }
  }

  public void testRWotherIndices() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      assertFalse(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx")).isMatch());
      assertFalse(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx")).isMatch());
      assertFalse(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx")).isMatch());
    }
  }

  public void testROmixedIndices() {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertTrue(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertTrue(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  public void testRWmixedIndices() {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      assertFalse(matchRule(KibanaAccess.RO_STRICT, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertFalse(matchRule(KibanaAccess.RO, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
      assertFalse(matchRule(KibanaAccess.RW, action, Sets.newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  public void testRWcustomKibanaIndex() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);

    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      System.out.println("trying " + action + " as RO_STRICT");
      assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true).isMatch());

      System.out.println("trying " + action + " as RO");
      assertFalse(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true).isMatch());

      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true).isMatch());
    }
  }

  public void testRONonStrictOperations() {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.newHashSet(customKibanaIndex);
    String action = "indices:data/write/index";
    String uri = "/" + customKibanaIndex + "/index-pattern/job";

    System.out.println("trying " + action + " as RO");
    assertFalse(matchRule(KibanaAccess.RO_STRICT, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RO, action, indices, customKibanaIndex, true, uri).isMatch());
    assertTrue(matchRule(KibanaAccess.RW, action, indices, customKibanaIndex, true, uri).isMatch());
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