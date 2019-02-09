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
package tech.beshu.ror.utils;

import cz.seznam.euphoria.shaded.guava.com.google.common.cache.Cache;
import cz.seznam.euphoria.shaded.guava.com.google.common.cache.CacheBuilder;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class InMemCache {
  private final Cache cache;
  private final Hasher hasher;
  private final String algo;

  public InMemCache(Duration ttl, String algo) {
    if (algo == null || "none".equals(algo.toLowerCase())) {
      this.hasher = str -> str;
    }
    else {
      this.hasher = new SecureStringHasher(algo);
    }
    this.cache = createInsecureCache(ttl);
    this.algo = algo;
  }

  public static Cache createInsecureCache(Duration ttl) {
    return CacheBuilder.newBuilder()
      .concurrencyLevel(Runtime.getRuntime().availableProcessors())
      .maximumSize(5000)
      .expireAfterWrite(ttl.getSeconds(), TimeUnit.SECONDS)
      .build();
  }

  public String getAlgo() {
    return algo;
  }

  public void put(String key, String value) {
    cache.put(hasher.hash(key), hasher.hash(value));
  }

  public boolean isHit(String key, String value) {
    return hasher.hash(value).equals(cache.getIfPresent(hasher.hash(key)));
  }
}
