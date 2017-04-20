package org.elasticsearch.plugin.readonlyrest.clients;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.Hashing;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.optionalAttributeValue;

public class CachedExternalAuthenticationServiceClient implements ExternalAuthenticationServiceClient {

  private final static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";

  private final ExternalAuthenticationServiceClient underlying;
  private final Cache<String, String> cache;

  public static ExternalAuthenticationServiceClient wrapInCacheIfCacheIsEnabled(Settings settings,
                                                                                ExternalAuthenticationServiceClient client) {
    return optionalAttributeValue(ATTRIBUTE_CACHE_TTL, settings, ConfigReaderHelper.toDuration())
        .map(ttl -> ttl.isZero()
            ? client
            : new CachedExternalAuthenticationServiceClient(client, ttl))
        .orElse(client);
  }

  private CachedExternalAuthenticationServiceClient(ExternalAuthenticationServiceClient underlying,
                                                    Duration ttl) {
    this.underlying = underlying;
    this.cache = CacheBuilder.newBuilder()
        .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
        .build();
  }

  @Override
  public CompletableFuture<Boolean> authenticate(String user, String password) {
    String cachedUserHashedPass = cache.getIfPresent(user);
    if (cachedUserHashedPass == null) {
      return underlying.authenticate(user, password)
          .thenApply(authenticated -> {
            if(authenticated) {
              cache.put(user, hashFrom(password));
            }
            return authenticated;
          });
    }
    return CompletableFuture.completedFuture(
        Objects.equals(cachedUserHashedPass, hashFrom(password))
    );
  }

  public static String hashFrom(String password) {
    return Hashing.sha256().hashString(password, Charset.defaultCharset()).toString();
  }
}
