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
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.SyncRule;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.AuthKeyUnixRuleSettings;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Created by samy-orange on 03/07/2017.
 */

public class AuthKeyUnixRuleTests {

  private RuleExitResult match(String configured, String found) {
    try {
      return match(configured, found, Mockito.mock(RequestContext.class));
    } catch (Throwable t){
      throw new Error(t);
    }
  }

  private RuleExitResult match(String configured, String found, RequestContext rc) throws ExecutionException, InterruptedException {
    when(rc.getHeaders()).thenReturn(ImmutableMap.of("Authorization", found));

    AsyncRule r = new AuthKeyUnixAsyncRule(new AuthKeyUnixRuleSettings(configured, Duration.ZERO), MockedESContext.INSTANCE);

    return r.match(rc).get();
  }

  @Test
  public void testSimple() {
    RuleExitResult res = match(
      "test:$6$rounds=65535$d07dnv4N$QeErsDT9Mz.ZoEPXW3dwQGL7tzwRz.eOrTBepIwfGEwdUAYSy/NirGoOaNyPx8lqiR6DYRSsDzVvVbhP4Y9wf0",
      "Basic " + Base64.getEncoder().encodeToString("test:test".getBytes())
    );
    assertTrue(res.isMatch());
  }

}
