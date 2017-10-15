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
package tech.beshu.ror.acl.blocks.rules;

import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.CacheSettings;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static tech.beshu.ror.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CachedAsyncAuthorizationDecoratorTests {

  private AsyncAuthorization dummyAsyncRule = new AsyncAuthorization(MockedESContext.INSTANCE) {
    @Override
    protected CompletableFuture<Boolean> authorize(LoggedUser user) {
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public String getKey() {
      return "";
    }
  };

  @Test
  public void testIfAsyncAuthorizationRuleIsWrappedInCacheIfOneIsEnabled() {
    CacheSettings settings = () -> Duration.ofSeconds(10);
    AsyncAuthorization authorization = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings, MockedESContext.INSTANCE);
    assertNotEquals(dummyAsyncRule, authorization);
  }

  @Test
  public void testIfAsyncAuthorizationRuleIsNotWrappedInCacheIfTtlIsZero() {
    CacheSettings settings = () -> Duration.ZERO;
    AsyncAuthorization authorization = wrapInCacheIfCacheIsEnabled(dummyAsyncRule, settings, MockedESContext.INSTANCE);
    assertEquals(dummyAsyncRule, authorization);
  }

  @Test
  public void testIfAuthorizationIsCached() throws Exception {
    LoggedUser user = new LoggedUser("tester");

    MockedAsyncAuthorization rule = Mockito.mock(MockedAsyncAuthorization.class);
    when(rule.authorize(any())).thenReturn(CompletableFuture.completedFuture(true));
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    CacheSettings settings = () -> Duration.ofSeconds(10);
    AsyncAuthorization cachedAuthorizationRule = wrapInCacheIfCacheIsEnabled(rule, settings, MockedESContext.INSTANCE);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthorizationRule.match(requestContext);
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthorizationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(1)).authorize(user);
  }

  @Test
  public void testIfCachedResultExpires() throws Exception {
    LoggedUser user = new LoggedUser("tester");
    Duration ttl = Duration.ofSeconds(1);

    MockedAsyncAuthorization rule = Mockito.mock(MockedAsyncAuthorization.class);
    when(rule.authorize(any())).thenReturn(CompletableFuture.completedFuture(true));
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    CacheSettings settings = () -> ttl;
    AsyncAuthorization cachedAuthorizationRule = wrapInCacheIfCacheIsEnabled(rule, settings, MockedESContext.INSTANCE);
    CompletableFuture<RuleExitResult> firstAttemptMatch = cachedAuthorizationRule.match(requestContext);
    Thread.sleep((long) (ttl.toMillis() * 1.5));
    CompletableFuture<RuleExitResult> secondAttemptMatch = cachedAuthorizationRule.match(requestContext);

    assertEquals(true, firstAttemptMatch.get().isMatch());
    assertEquals(true, secondAttemptMatch.get().isMatch());
    verify(rule, times(2)).authorize(user);
  }

  private abstract class MockedAsyncAuthorization extends AsyncAuthorization {
    protected MockedAsyncAuthorization(ESContext context) {
      super(context);
    }

    @Override
    public abstract CompletableFuture<Boolean> authorize(LoggedUser user);
  }
}
