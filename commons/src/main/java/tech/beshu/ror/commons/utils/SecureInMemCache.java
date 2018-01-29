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
package tech.beshu.ror.commons.utils;


import cz.seznam.euphoria.shaded.guava.com.google.common.cache.Cache;
import cz.seznam.euphoria.shaded.guava.com.google.common.cache.CacheBuilder;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.Hashing;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SecureInMemCache<T extends Serializable> {

  private static String salt = UUID.randomUUID().toString();
  private final Cache<String, T> cache;

  public SecureInMemCache(Duration ttl) {
    this.cache = CacheBuilder.newBuilder()
      .concurrencyLevel(Runtime.getRuntime().availableProcessors())
      .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
      .build();
  }

  private String hashCacheKey(String originalKey) {
    return Hashing.sha256()
      .hashString(originalKey.concat(salt), StandardCharsets.UTF_8)
      .toString();
  }

  public T getIfPresent(String key) {
    return cache.getIfPresent(hashCacheKey(key));
  }

  public void put(String key, T value) {
    cache.put(hashCacheKey(key), value);
  }
}
