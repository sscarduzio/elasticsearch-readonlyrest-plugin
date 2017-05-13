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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.settings.rules.CacheSettings;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CachedGroupsProviderServiceClient implements GroupsProviderServiceClient {

  private final GroupsProviderServiceClient underlying;
  private final Cache<String, Set<String>> cache;

  public static GroupsProviderServiceClient wrapInCacheIfCacheIsEnabled(CacheSettings settings,
                                                                        GroupsProviderServiceClient client) {
    return settings.getCacheTtl().isZero()
        ? client
        : new CachedGroupsProviderServiceClient(client, settings.getCacheTtl());
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
