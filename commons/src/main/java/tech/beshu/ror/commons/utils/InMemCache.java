package tech.beshu.ror.commons.utils;

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
    System.out.println("check if hit: " + key + ":" + value + " -> "  + cache.getIfPresent(hasher.hash(key)) == hasher.hash(value));
    return cache.getIfPresent(hasher.hash(key)) == hasher.hash(value);
  }
}
