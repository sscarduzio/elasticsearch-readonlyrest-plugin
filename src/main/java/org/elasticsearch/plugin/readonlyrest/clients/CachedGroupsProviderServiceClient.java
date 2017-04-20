package org.elasticsearch.plugin.readonlyrest.clients;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.optionalAttributeValue;

public class CachedGroupsProviderServiceClient implements GroupsProviderServiceClient {

  private final static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";

  private final GroupsProviderServiceClient underlying;
  private final Cache<String, Set<String>> cache;

  public static GroupsProviderServiceClient wrapInCacheIfCacheIsEnabled(Settings settings,
                                                                        GroupsProviderServiceClient client) {
    return optionalAttributeValue(ATTRIBUTE_CACHE_TTL, settings, ConfigReaderHelper.toDuration())
        .map(ttl -> ttl.isZero()
            ? client
            : new CachedGroupsProviderServiceClient(client, ttl))
        .orElse(client);
  }

  private CachedGroupsProviderServiceClient(GroupsProviderServiceClient underlying,
                                            Duration ttl) {
    this.underlying = underlying;
    this.cache = CacheBuilder.newBuilder()
        .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
        .build();
  }

  @Override
  public CompletableFuture<Set<String>> fetchGroupsFor(LoggedUser user) {
    Set<String> cachedUserGroups = cache.getIfPresent(user.getId());
    if (cachedUserGroups == null) {
      return underlying.fetchGroupsFor(user)
          .thenApply(groups -> {
            cache.put(user.getId(), groups);
            return groups;
          });
    }
    return CompletableFuture.completedFuture(cachedUserGroups);
  }
}
