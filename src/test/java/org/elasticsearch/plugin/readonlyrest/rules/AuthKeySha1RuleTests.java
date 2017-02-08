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

import com.google.common.collect.ImmutableMap;
import junit.framework.TestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.mockito.Mockito;

import java.util.Base64;

import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class AuthKeySha1RuleTests extends TestCase {

  private RuleExitResult match(String configured, String found) throws RuleNotConfiguredException {
    return match(configured, found, Mockito.mock(RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) throws RuleNotConfiguredException {
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("Authorization", found));

    SyncRule r = new AuthKeySha1SyncRule(Settings.builder()
                                   .put("auth_key_sha1", configured)
                                   .build());

    RuleExitResult res = r.match(rc);
    rc.commit();
    return res;
  }

  public void testSimple() throws RuleNotConfiguredException {
    RuleExitResult res = match(
      "4338fa3ea95532196849ae27615e14dda95c77b1",
      "Basic " + Base64.getEncoder().encodeToString("logstash:logstash".getBytes())
    );
    assertTrue(res.isMatch());
  }


}
