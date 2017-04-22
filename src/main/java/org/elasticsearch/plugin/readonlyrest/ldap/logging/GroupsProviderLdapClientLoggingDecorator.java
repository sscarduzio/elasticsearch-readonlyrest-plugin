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

import com.google.common.base.Joiner;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;
import org.elasticsearch.plugin.readonlyrest.ldap.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class GroupsProviderLdapClientLoggingDecorator implements GroupsProviderLdapClient {

  private final Logger logger;
  private final GroupsProviderLdapClient underlying;
  private final String name;
  private final AuthenticationLdapClientLoggingDecorator authenticationLdapClientLoggingDecorator;

  public GroupsProviderLdapClientLoggingDecorator(String name, ESContext context, GroupsProviderLdapClient underlying) {
    this.logger = context.logger(getClass());
    this.name = name;
    this.underlying = underlying;
    authenticationLdapClientLoggingDecorator = new AuthenticationLdapClientLoggingDecorator(name, context, underlying);
  }

  public static GroupsProviderLdapClient wrapInLoggingIfIsLoggingEnabled(String name, ESContext context,
                                                                         GroupsProviderLdapClient client) {
    return context.logger(GroupsProviderLdapClientLoggingDecorator.class).isDebugEnabled()
        ? new GroupsProviderLdapClientLoggingDecorator(name, context, client)
        : client;
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
    return authenticationLdapClientLoggingDecorator.userById(userId);
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
    return authenticationLdapClientLoggingDecorator.authenticate(credentials);
  }
}
