package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.optionalAttributeValue;

public class CachedAsyncAuthorizationDecorator extends AsyncAuthorization {

  private static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";

  private final AsyncAuthorization underlying;
  private final Cache<UserWithRoles, Boolean> cache;

  public static AsyncAuthorization wrapInCacheIfCacheIsEnabled(AsyncAuthorization authorization, Settings settings) {
    return optionalAttributeValue(ATTRIBUTE_CACHE_TTL, settings, ConfigReaderHelper.toDuration())
        .map(ttl -> (AsyncAuthorization) new CachedAsyncAuthorizationDecorator(authorization, ttl))
        .orElse(authorization);
  }

  public CachedAsyncAuthorizationDecorator(AsyncAuthorization underlying, Duration ttl) {
    this.underlying = underlying;
    this.cache = CacheBuilder.newBuilder()
        .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
        .build();
  }

  @Override
  public CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles) {
    UserWithRoles userWithRoles = new UserWithRoles(user, roles);
    Boolean authorizationResult = cache.getIfPresent(userWithRoles);
    if (authorizationResult == null) {
      return underlying.authorize(user, roles)
          .thenApply(result -> {
            cache.put(userWithRoles, result);
            return result;
          });
    }
    return CompletableFuture.completedFuture(authorizationResult);
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    return underlying.match(rc);
  }

  @Override
  public String getKey() {
    return underlying.getKey();
  }

  private static class UserWithRoles {

    private final LoggedUser user;
    private final Set<String> roles;

    public UserWithRoles(LoggedUser user, Set<String> roles) {
      this.user = user;
      this.roles = roles;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) return false;
      if (getClass() != obj.getClass()) return false;
      final UserWithRoles other = (UserWithRoles) obj;
      return Objects.equals(user, other.user) &&
          roles.equals(other.roles);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.user, this.roles);
    }
  }

}
