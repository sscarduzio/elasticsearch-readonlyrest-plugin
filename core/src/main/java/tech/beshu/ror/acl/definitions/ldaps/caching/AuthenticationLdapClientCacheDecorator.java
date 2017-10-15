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
package tech.beshu.ror.acl.definitions.ldaps.caching;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import tech.beshu.ror.acl.definitions.ldaps.AuthenticationLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.LdapCredentials;
import tech.beshu.ror.acl.definitions.ldaps.LdapUser;
import tech.beshu.ror.settings.rules.CacheSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AuthenticationLdapClientCacheDecorator implements AuthenticationLdapClient {

  private final AuthenticationLdapClient underlyingClient;
  private final Cache<String, LdapUserWithHashedPassword> ldapUsersWithPasswordCache;
  private final Cache<String, Optional<LdapUser>> ldapUsersCache;

  public AuthenticationLdapClientCacheDecorator(AuthenticationLdapClient underlyingClient, Duration ttl) {
    this.underlyingClient = underlyingClient;
    this.ldapUsersWithPasswordCache = CacheBuilder.newBuilder()
      .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
      .build();
    this.ldapUsersCache = CacheBuilder.newBuilder()
      .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
      .build();
  }

  public static AuthenticationLdapClient wrapInCacheIfCacheIsEnabled(CacheSettings settings, AuthenticationLdapClient client) {
    return settings.getCacheTtl().isZero()
      ? client
      : new AuthenticationLdapClientCacheDecorator(client, settings.getCacheTtl());
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> userById(String userId) {
    Optional<LdapUser> ldapUser = ldapUsersCache.getIfPresent(userId);
    if (ldapUser == null) {
      return underlyingClient.userById(userId)
        .thenApply(user -> {
          ldapUsersCache.put(userId, user);
          return user;
        });
    }
    return CompletableFuture.completedFuture(ldapUser);
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
    LdapUserWithHashedPassword cachedUser = ldapUsersWithPasswordCache.getIfPresent(credentials.getUserName());
    if (cachedUser == null) {
      return underlyingClient.authenticate(credentials)
        .thenApply(newUser -> {
          newUser.ifPresent(ldapUser -> ldapUsersWithPasswordCache.put(
            credentials.getUserName(),
            new LdapUserWithHashedPassword(ldapUser, credentials.getHashedPassword())
          ));
          return newUser;
        });
    }
    return CompletableFuture.completedFuture(
      Objects.equals(cachedUser.hashedPassword, credentials.getHashedPassword())
        ? Optional.of(cachedUser.ldapUser)
        : Optional.empty()
    );
  }

  private static class LdapUserWithHashedPassword {
    private final LdapUser ldapUser;
    private final String hashedPassword;

    LdapUserWithHashedPassword(LdapUser ldapUser, String hashedPassword) {
      this.ldapUser = ldapUser;
      this.hashedPassword = hashedPassword;
    }
  }
}
