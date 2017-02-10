package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class LdapClientWithCacheDecorator implements LdapClient {

    private final LdapClient underlyingClient;
    private final Cache<String, LdapUser> cache;

    public LdapClientWithCacheDecorator(LdapClient underlyingClient, Duration ttl) {
        this.underlyingClient = underlyingClient;
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
        LdapUser user = cache.getIfPresent(credentials.getUserName());
        if(user == null) {
            return underlyingClient.authenticate(credentials)
                    .thenApply(newUser -> {
                        newUser.ifPresent(ldapUser -> cache.put(credentials.getUserName(), ldapUser));
                        return newUser;
                    });
        }
        return CompletableFuture.completedFuture(Optional.of(user));
    }
}
