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
package org.elasticsearch.plugin.readonlyrest.ldap.logging;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ldap.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AuthenticationLdapClientLoggingDecorator implements AuthenticationLdapClient {

  private static final ESLogger logger =  Loggers.getLogger(AuthenticationLdapClientLoggingDecorator.class);

  private final AuthenticationLdapClient underlying;
  private final String name;

  public AuthenticationLdapClientLoggingDecorator(String name, AuthenticationLdapClient underlying) {
    this.name = name;
    this.underlying = underlying;
  }

  public static AuthenticationLdapClient wrapInLoggingIfIsLoggingEnabled(String name, AuthenticationLdapClient client) {
    return logger.isDebugEnabled()
        ? new AuthenticationLdapClientLoggingDecorator(name, client)
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
                       logger.debug(user.isPresent()
                           ? "User with identifier [" + userId + "] found [dn = " + user.get().getDN() + "]"
                           : "User with  identifier [" + userId + "] not found"
                       );
                       return user;
                     });
  }
}
