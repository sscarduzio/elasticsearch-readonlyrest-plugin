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

import com.google.common.collect.Sets;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CachedAsyncAuthorizationDecoratorTests {

  @Test
  public void testIfAsyncAuthorizationRuleIsWrappedInCacheIfOneIsEnabled() {
    Settings settings = Settings.builder().put("cache_ttl_in_sec", "10").build();
    AsyncAuthorization authorization = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertNotEquals(dummyAsyncRule, authorization);
  }

  @Test
  public void testIfAsyncAuthorizationRuleIsNotWrappedInCacheIfOneIsNotEnabled() {
    Settings settings = Settings.builder().put("other_setting", "value").build();
    AsyncAuthorization authorization = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertEquals(dummyAsyncRule, authorization);
  }

  @Test
  public void testIfAsyncAuthorizationRuleIsNotWrappedInCacheIfTtlIsZero() {
    Settings settings = Settings.builder().put("cache_ttl_in_sec", "0").build();
    AsyncAuthorization authorization = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings);
    assertEquals(dummyAsyncRule, authorization);
  }

  @Test
  public void testIfAuthorizationIsCached() throws Exception {
    LoggedUser user = new LoggedUser("tester");
    Set<String> groups = Sets.newHashSet("group1", "group2");

    MockedAsyncAuthorization rule = Mockito.mock(MockedAsyncAuthorization.class);
    when(rule.authorize(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    when(rule.getGroups()).thenReturn(groups);
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    Settings settings = Settings.builder().put("cache_ttl_in_sec", "10").build();
    AsyncAuthorization cachedAuthorizationRule = wrapInCacheIfCacheIsEnabled(rule, settings);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthorizationRule.match(requestContext);
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthorizationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(1)).authorize(user, groups);
  }

  @Test
  public void testIfCachedResultExpires() throws Exception {
    LoggedUser user = new LoggedUser("tester");
    Set<String> groups = Sets.newHashSet("group1", "group2");
    Duration ttl = Duration.ofSeconds(1);

    MockedAsyncAuthorization rule = Mockito.mock(MockedAsyncAuthorization.class);
    when(rule.authorize(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    when(rule.getGroups()).thenReturn(groups);
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    Settings settings = Settings.builder().put("cache_ttl_in_sec", ttl.getSeconds()).build();
    AsyncAuthorization cachedAuthorizationRule = wrapInCacheIfCacheIsEnabled(rule, settings);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthorizationRule.match(requestContext);
    Thread.sleep((long) (ttl.toMillis() * 1.5));
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthorizationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(2)).authorize(user, groups);
  }

  private AsyncAuthorization dummyAsyncRule = new AsyncAuthorization() {
    @Override
    protected CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> groups) {
      return CompletableFuture.completedFuture(true);
    }

    @Override
    protected Set<String> getGroups() {
      return Sets.newHashSet();
    }

    @Override
    public String getKey() {
      return "";
    }
  };

  private abstract class MockedAsyncAuthorization extends AsyncAuthorization {
    @Override
    public abstract CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> groups);

    @Override
    public abstract Set<String> getGroups();
  }
}
