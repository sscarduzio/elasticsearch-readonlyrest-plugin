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
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.InMemCache;
import tech.beshu.ror.settings.rules.CacheSettings;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class CachedAsyncAuthenticationDecorator extends AsyncAuthentication {

  private final AsyncAuthentication underlying;
  private final InMemCache cache;
  private final LoggerShim logger;

  public CachedAsyncAuthenticationDecorator(AsyncAuthentication underlying, Duration ttl, ESContext context) {
    super(context);
    this.underlying = underlying;
    this.logger = context.logger(getClass());
    String algo = context.getSettings().getCacheHashingAlgo();
    InMemCache sCache = new InMemCache(ttl, algo);
    logger.info("Initialized " + underlying.getKey() + " with cache (hashing algorithm " + sCache.getAlgo() + ", TTL: " + ttl.getSeconds() + "s)");
    this.cache = sCache;
  }

  public static AsyncAuthentication wrapInCacheIfCacheIsEnabled(AsyncAuthentication authentication,
                                                                CacheSettings settings,
                                                                ESContext context) {
    return settings.getCacheTtl().isZero()
      ? authentication
      : new CachedAsyncAuthenticationDecorator(authentication, settings.getCacheTtl(), context);
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {

    boolean hit = cache.isHit(user, password);
    if (!hit) {
      return underlying.authenticate(user, password)
        .thenApply(result -> {
          if (result) {
            cache.put(user, password);
          }
          return result;
        });
    }
    return CompletableFuture.completedFuture(hit);
  }

  @Override
  public String getKey() {
    return underlying.getKey();
  }

  public AsyncAuthentication getUnderlying() {
    return underlying;
  }
}
