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
import cz.seznam.euphoria.shaded.guava.com.google.common.cache.CacheStats;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.ImmutableMap;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.HashCode;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.HashFunction;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.Hashing;
import cz.seznam.euphoria.shaded.guava.com.google.common.io.BaseEncoding;
import cz.seznam.euphoria.shaded.guava.com.google.common.primitives.Bytes;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class SecureInMemCache<K, T> implements Cache<K, T> {

  private static byte[] salt;
  private final Cache<String, T> cache;
  private final HashFunction hf;

  public SecureInMemCache(Duration ttl, String algo) {

    this.cache = createInsecureCache(ttl);
    int saltSize;
    switch (algo.toLowerCase()) {
      case "sha256":
        hf = Hashing.sha256();
        saltSize = 256;
        break;
      case "sha348":
        hf = Hashing.sha384();
        saltSize = 384;
        break;
      case "sha512":
        hf = Hashing.sha512();
        saltSize = 512;
        break;
      default:
        hf = Hashing.sha256();
        saltSize = 256;
    }
    salt = SecureRandom.getSeed(saltSize);
  }

  public static Cache createInsecureCache(Duration ttl) {
    return CacheBuilder.newBuilder()
      .concurrencyLevel(Runtime.getRuntime().availableProcessors())
      .maximumSize(5000)
      .expireAfterWrite(ttl.getSeconds(), TimeUnit.SECONDS)
      .build();
  }

  private static void rotate(byte[] arr, int order) {
    if (arr == null || order < 0) {
      throw new IllegalArgumentException("The array must be non-null and the order must be non-negative");
    }
    int offset = arr.length - order % arr.length;
    if (offset > 0) {
      byte[] copy = arr.clone();
      for (int i = 0; i < arr.length; ++i) {
        int j = (i + offset) % arr.length;
        arr[i] = copy[j];
      }
    }
  }

  private String hashCacheKey(String originalKey) {
    int order = Hashing.consistentHash(HashCode.fromString(BaseEncoding.base16().encode(originalKey.getBytes())), salt.length);
    byte[] thisSalt = salt.clone();
    rotate(thisSalt, Math.abs(order));
    return hf.hashBytes(Bytes.concat(originalKey.getBytes(), thisSalt)).toString();
  }

  @Override
  public T getIfPresent(Object key) {
    return cache.getIfPresent(hashCacheKey((String) key));
  }


  @Override
  public T get(K key, Callable<? extends T> loader) throws ExecutionException {
    return cache.get(hashCacheKey((String) key), loader);
  }

  @Override
  public ImmutableMap<K, T> getAllPresent(Iterable<?> keys) {
    throw new UnsupportedOperationException();
  }

  public void put(K key, T value) {
    cache.put(hashCacheKey((String) key), value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends T> m) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void invalidate(Object key) {
    cache.invalidate(hashCacheKey((String) key));
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    keys.forEach(k -> cache.invalidate(hashCacheKey((String) k)));
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }

  @Override
  public long size() {
    return cache.size();
  }

  @Override
  public CacheStats stats() {
    return cache.stats();
  }

  @Override
  public ConcurrentMap<K, T> asMap() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void cleanUp() {
    cache.cleanUp();
  }
}
