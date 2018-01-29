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
