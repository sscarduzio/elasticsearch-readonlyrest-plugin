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

package tech.beshu.ror.unit.acl.definitions.externalauthenticationservices;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.Hashing;
import tech.beshu.ror.settings.rules.CacheSettings;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CachedExternalAuthenticationServiceClient implements ExternalAuthenticationServiceClient {

  private final ExternalAuthenticationServiceClient underlying;
  private final Cache<String, String> cache;

  private CachedExternalAuthenticationServiceClient(ExternalAuthenticationServiceClient underlying,
      Duration ttl) {
    this.underlying = underlying;
    this.cache = CacheBuilder.newBuilder()
                             .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                             .build();
  }

  public static ExternalAuthenticationServiceClient wrapInCacheIfCacheIsEnabled(CacheSettings settings,
      ExternalAuthenticationServiceClient client) {
    return settings.getCacheTtl().isZero()
        ? client
        : new CachedExternalAuthenticationServiceClient(client, settings.getCacheTtl());
  }

  public static ExternalAuthenticationServiceClient wrapInCacheIfCacheIsEnabled(
      CacheSettings settings, ExternalAuthenticationServiceHttpClient client) {

    return settings.getCacheTtl().isZero()
        ? client
        : new CachedExternalAuthenticationServiceClient(client, settings.getCacheTtl());
  }

  private static String hashFrom(String password) {
    return Hashing.sha256().hashString(password, Charset.defaultCharset()).toString();
  }

  @Override
  public CompletableFuture<Boolean> authenticate(String user, String password) {
    String cachedUserHashedPass = cache.getIfPresent(user);
    if (cachedUserHashedPass == null) {
      return underlying.authenticate(user, password)
                       .thenApply(authenticated -> {
                         if (authenticated) {
                           cache.put(user, hashFrom(password));
                         }
                         return authenticated;
                       });
    }
    return CompletableFuture.completedFuture(
        Objects.equals(cachedUserHashedPass, hashFrom(password))
    );
  }
}
