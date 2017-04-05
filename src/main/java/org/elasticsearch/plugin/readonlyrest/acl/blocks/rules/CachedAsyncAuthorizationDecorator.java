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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
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
        .map(ttl -> ttl.isZero()
            ? authorization
            : new CachedAsyncAuthorizationDecorator(authorization, ttl))
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
  protected Set<String> getRoles() {
    return underlying.getRoles();
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
