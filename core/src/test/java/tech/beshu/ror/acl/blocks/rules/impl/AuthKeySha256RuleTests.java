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

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.rules.AuthKeySha256RuleSettings;

import java.util.Base64;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by sscarduzio on 18/01/2017.
 */

public class AuthKeySha256RuleTests {

  private RuleExitResult match(String configured, String found) {
    return match(configured, found, Mockito.mock(__old_RequestContext.class));
  }

  private RuleExitResult match(String configured, String found, __old_RequestContext rc) {
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("Authorization", found));

    SyncRule r = new __old_AuthKeySha256SyncRule(new AuthKeySha256RuleSettings(configured), MockedESContext.INSTANCE);

    return r.match(rc);
  }

  @Test
  public void testSimple() {
    RuleExitResult res = match(
      "280ac6f756a64a80143447c980289e7e4c6918b92588c8095c7c3f049a13fbf9",
      "Basic " + Base64.getEncoder().encodeToString("logstash:logstash".getBytes())
    );
    assertTrue(res.isMatch());
  }
}
