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
import com.google.common.hash.Hashing;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.settings.rules.CacheSettings;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CachedAsyncAuthenticationDecorator extends AsyncAuthentication {

  private final AsyncAuthentication underlying;
  private final Cache<String, String> cache;

  public CachedAsyncAuthenticationDecorator(AsyncAuthentication underlying, Duration ttl, ESContext context) {
    super(context);
    this.underlying = underlying;
    this.cache = CacheBuilder.newBuilder()
                             .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                             .build();
  }

  public static AsyncAuthentication wrapInCacheIfCacheIsEnabled(AsyncAuthentication authentication,
                                                                CacheSettings settings,
                                                                ESContext context) {
    return settings.getCacheTtl().isZero()
        ? authentication
        : new CachedAsyncAuthenticationDecorator(authentication, settings.getCacheTtl(), context);
  }

  private static String hashPassword(String password) {
    return Hashing.sha256().hashString(password, Charset.defaultCharset()).toString();
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    String hashedPassword = cache.getIfPresent(user);
    String providedHashedPassword = hashPassword(password);
    if (hashedPassword == null) {
      return underlying.authenticate(user, password)
                       .thenApply(result -> {
                         if (result) {
                           cache.put(user, providedHashedPassword);
                         }
                         return result;
                       });
    }
    return CompletableFuture.completedFuture(Objects.equals(hashedPassword, providedHashedPassword));
  }

  @Override
  public String getKey() {
    return underlying.getKey();
  }

  public AsyncAuthentication getUnderlying() {
    return underlying;
  }
}
