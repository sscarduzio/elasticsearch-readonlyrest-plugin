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
package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.base.Joiner;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapClientWithLoggingDecorator implements LdapClient {

  private static final Logger logger = Loggers.getLogger(LdapClientWithLoggingDecorator.class);

  private final LdapClient underlying;
  private final String name;

  public static LdapClient wrapInLoggingIfIsLoggingEnabled(String name, LdapClient client) {
    return logger.isDebugEnabled()
        ? new LdapClientWithLoggingDecorator(name, client)
        : client;
  }

  public LdapClientWithLoggingDecorator(String name, LdapClient underlying) {
    this.name = name;
    this.underlying = underlying;
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
    logger.debug("Trying to authenticate user [" + credentials.getUserName() + "] with LDAP [" + name + "]");
    return underlying.authenticate(credentials)
        .thenApply(user -> {
          logger.debug("User [" + credentials.getUserName() + "] " + (user.isPresent() ? "" : "not") +
              " authenticated by LDAP [" + name + "]");
          return user;
        });
  }

  @Override
  public CompletableFuture<Set<LdapGroup>> userGroups(LdapUser user) {
    logger.debug("Trying to fetch user [id=" + user.getUid() + ", dn" + user.getDN() + "] groups from LDAP [" + name + "]");
    return underlying.userGroups(user)
        .thenApply(groups -> {
          logger.debug("LDAP [" + name + "] returned for user [" + user.getUid() + "] following groups: " +
              "[" + Joiner.on(", ").join(groups.stream().map(LdapGroup::getName).collect(Collectors.toSet())) + "]");
          return groups;
        });
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> userById(String userId) {
    logger.debug("Trying to fetch user with identifier [" + userId + "] from LDAP [" + name + "]");
    return underlying.userById(userId)
        .thenApply(user -> {
          logger.debug(user.isPresent()
              ? "User with identifier [" + userId + "] found [dn = " + user.get().getDN() + "]"
              : "User with  identifier [" + userId + "] not found"
          );
          return user;
        });
  }
}
