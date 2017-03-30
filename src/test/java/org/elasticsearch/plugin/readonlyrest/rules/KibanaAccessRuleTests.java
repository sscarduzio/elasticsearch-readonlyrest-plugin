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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.mockito.Mockito;

import java.util.Set;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class KibanaAccessRuleTests extends TestCase {
  private RuleExitResult match(Conf configured, Found found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(Conf configured, Found found, RequestContext rc) throws RuleNotConfiguredException {
    return match(configured,found,rc,false);
  }
  private RuleExitResult match(Conf configured, Found found, RequestContext rc, boolean involvesIndices) throws RuleNotConfiguredException {
    when(rc.involvesIndices()).thenReturn(involvesIndices);
    when(rc.getIndices()).thenReturn(found.indices);
    when(rc.getAction()).thenReturn(found.action);

    SyncRule r = new KibanaAccessSyncRule(
      Settings.builder()
        .put("kibana_access", configured.accessLevel)
        .put("kibana_index", configured.kibanaIndex)
        .build()
    );

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }

  public RuleExitResult matchRule(String accessLevel, String action) throws RuleNotConfiguredException {
    return matchRule(accessLevel, action, Sets.<String>newHashSet(".kibana"));
  }

  public RuleExitResult matchRule(String accessLevel, String action, Set<String> indices) throws RuleNotConfiguredException {
    return matchRule(accessLevel, action, indices, ".kibana", false);

  }

  public RuleExitResult matchRule(String accessLevel, String action, Set<String> indices, String kibanaIndex, boolean involvesIndices)
    throws RuleNotConfiguredException {
    Conf conf = new Conf();
    conf.accessLevel = accessLevel;
    conf.kibanaIndex = kibanaIndex;

    Found found = new Found();
    found.action = action;
    found.indices = indices;

    return match(conf, found, Mockito.mock(RequestContext.class), true);
  }

  public void testCLUSTER() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.CLUSTER.getMatchers()) {
      System.out.println("trying " + action + " as RO");
      assertTrue(matchRule("ro", action).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule("rw", action).isMatch());
    }
  }

  public void testRO() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      System.out.println("trying " + action + " as RO");
      assertTrue(matchRule("ro", action).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule("rw", action).isMatch());
    }
  }

  public void testRW() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      String a = action.replace("*", "_");
      if (action.equals("indices:admin/exists")) {
        System.out.println("");
      }
      System.out.println("trying " + a + " as RO");
      assertFalse(matchRule("ro", a, Sets.newHashSet(".kibana"), ".kibana", true).isMatch());
      System.out.println("trying " + a + " as RW");
      assertTrue(matchRule("rw", a, Sets.newHashSet(".kibana"), ".kibana", true).isMatch());
    }
  }

  public void testROotherIndices() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule("ro", action, Sets.<String>newHashSet("xxx")).isMatch());
      assertTrue(matchRule("rw", action, Sets.<String>newHashSet("xxx")).isMatch());
    }
  }

  public void testRWotherIndices() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      assertFalse(matchRule("ro", action, Sets.<String>newHashSet("xxx")).isMatch());
      assertFalse(matchRule("rw", action, Sets.<String>newHashSet("xxx")).isMatch());
    }
  }

  public void testROmixedIndices() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RO.getMatchers()) {
      assertTrue(matchRule("ro", action, Sets.<String>newHashSet("xxx", ".kibana")).isMatch());
      assertTrue(matchRule("rw", action, Sets.<String>newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  public void testRWmixedIndices() throws RuleNotConfiguredException {
    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      assertFalse(matchRule("ro", action, Sets.<String>newHashSet("xxx", ".kibana")).isMatch());
      assertFalse(matchRule("rw", action, Sets.<String>newHashSet("xxx", ".kibana")).isMatch());
    }
  }

  public void testRWcustomKibanaIndex() throws RuleNotConfiguredException {
    String customKibanaIndex = ".kibana-custom";
    Set<String> indices = Sets.<String>newHashSet(customKibanaIndex);

    for (String action : KibanaAccessSyncRule.RW.getMatchers()) {
      System.out.println("trying " + action + " as RO");
      assertFalse(matchRule("ro", action, indices, customKibanaIndex, false).isMatch());
      System.out.println("trying " + action + " as RW");
      assertTrue(matchRule("rw", action, indices, customKibanaIndex, false).isMatch());
    }
  }

  class Conf {
    public String accessLevel = "ro";
    public String kibanaIndex = ".kibana";
  }

  class Found {
    public String action = null;
    public Set<String> indices = Sets.newHashSet(".kibana");
  }
}