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

import junit.framework.TestCase;
import org.elasticsearch.plugin.readonlyrest.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MaxBodyLengthRuleSettings;
import org.mockito.Mockito;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */
public class MaxBodyLengthRuleTests extends TestCase {

  private RuleExitResult match(Integer configured, String found) {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(Integer configured, String found, RequestContext rc) {
    when(rc.getContent()).thenReturn(found);

    SyncRule r = new MaxBodyLengthSyncRule(MaxBodyLengthRuleSettings.from(configured));

    return r.match(rc);
  }

  public void testShortEnuf() {
    RuleExitResult res = match(5, "xx");
    assertTrue(res.isMatch());
  }

  public void testEmpty() {
    RuleExitResult res = match(5, "");
    assertTrue(res.isMatch());
  }

  public void testTooLong() {
    RuleExitResult res = match(5, "hello123123");
    assertFalse(res.isMatch());
  }

}
