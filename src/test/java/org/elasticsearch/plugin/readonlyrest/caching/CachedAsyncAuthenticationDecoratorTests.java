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
package org.elasticsearch.plugin.readonlyrest.caching;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CachedAsyncAuthenticationDecoratorTests {

  private BasicAsyncAuthentication dummyAsyncRule = new BasicAsyncAuthentication() {

    @Override
    protected CompletableFuture<Boolean> authenticate(String user, String password) {
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public String getKey() {
      return "";
    }
  };

  @Test
  public void testIfAsyncAuthenticationRuleIsWrappedInCacheIfOneIsEnabled() {
    Settings settings = Settings.builder().put("cache_ttl_in_sec", "10").build();
    BasicAsyncAuthentication authentication = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertNotEquals(dummyAsyncRule, authentication);
  }

  @Test
  public void testIfAsyncAuthenticationRuleIsNotWrappedInCacheIfOneIsNotEnabled() {
    Settings settings = Settings.builder().put("other_setting", "value").build();
    BasicAsyncAuthentication authentication = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertEquals(dummyAsyncRule, authentication);
  }

  @Test
  public void testIfAsyncAuthenticationRuleIsNotWrappedInCacheIfTtlIsZero() {
    Settings settings = Settings.builder().put("cache_ttl_in_sec", "0").build();
    BasicAsyncAuthentication authentication = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertEquals(dummyAsyncRule, authentication);
  }

  @Test
  public void testIfAuthenticationIsCached() throws Exception {
    String user = "tester";
    String password = "password";

    MockedBasicAsyncAuthentication rule = Mockito.mock(MockedBasicAsyncAuthentication.class);
    when(rule.authenticate(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getHeaders()).thenReturn(
        ImmutableMap.<String, String>builder().put("Authorization", "Basic dGVzdGVyOnBhc3N3b3Jk").build()
    );

    Settings settings = Settings.builder().put("cache_ttl_in_sec", "10").build();
    BasicAsyncAuthentication cachedAuthenticationRule = wrapInCacheIfCacheIsEnabled(rule, settings);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthenticationRule.match(requestContext);
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthenticationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(1)).authenticate(user, password);
  }

  @Test
  public void testIfCachedResultExpires() throws Exception {
    String user = "tester";
    String password = "password";
    Duration ttl = Duration.ofSeconds(1);

    MockedBasicAsyncAuthentication rule = Mockito.mock(MockedBasicAsyncAuthentication.class);
    when(rule.authenticate(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getHeaders()).thenReturn(
        ImmutableMap.<String, String>builder().put("Authorization", "Basic dGVzdGVyOnBhc3N3b3Jk").build()
    );

    Settings settings = Settings.builder().put("cache_ttl_in_sec", ttl.getSeconds()).build();
    BasicAsyncAuthentication cachedAuthenticationRule = wrapInCacheIfCacheIsEnabled(rule, settings);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthenticationRule.match(requestContext);
    Thread.sleep((long) (ttl.toMillis() * 1.5));
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthenticationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(2)).authenticate(user, password);
  }

  private abstract class MockedBasicAsyncAuthentication extends BasicAsyncAuthentication {
    public abstract CompletableFuture<Boolean> authenticate(String user, String password);
  }
}
