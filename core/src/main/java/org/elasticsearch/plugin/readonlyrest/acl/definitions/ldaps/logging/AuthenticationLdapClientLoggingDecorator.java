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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.logging;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AuthenticationLdapClientLoggingDecorator implements AuthenticationLdapClient {

  private final LoggerShim logger;
  private final AuthenticationLdapClient underlying;
  private final String name;

  public AuthenticationLdapClientLoggingDecorator(String name, ESContext context, AuthenticationLdapClient underlying) {
    this.logger = context.logger(getClass());
    this.name = name;
    this.underlying = underlying;
  }

  public static AuthenticationLdapClient wrapInLoggingIfIsLoggingEnabled(String name, ESContext context,
                                                                         AuthenticationLdapClient client) {
    LoggerShim logger = context.logger(AuthenticationLdapClientLoggingDecorator.class);
    return logger.isDebugEnabled()
        ? new AuthenticationLdapClientLoggingDecorator(name, context, client)
        : client;
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
  public CompletableFuture<Optional<LdapUser>> userById(String userId) {
    logger.debug("Trying to fetch user with identifier [" + userId + "] from LDAP [" + name + "]");
    return underlying.userById(userId)
        .thenApply(user -> {
          logger.debug(
              user.map(ldapUser -> "User with identifier [" + userId + "] found [dn = " + ldapUser.getDN() + "]")
                  .orElseGet(() -> "User with  identifier [" + userId + "] not found")
          );
          return user;
        });
  }
}
