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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthorizationAsyncRule extends AsyncAuthorization {

  private final GroupsProviderLdapClient client;
  private final LdapAuthorizationRuleSettings settings;

  public LdapAuthorizationAsyncRule(LdapAuthorizationRuleSettings settings, LdapClientFactory factory, ESContext context) {
    super(context);
    this.client = factory.getClient(settings.getLdapSettings());
    this.settings = settings;
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    return client.userById(user.getId())
        .thenCompose(ldapUser -> ldapUser
            .map(client::userGroups)
            .orElseGet(() -> CompletableFuture.completedFuture(Sets.newHashSet()))
        )
        .thenApply(this::checkIfUserHasAccess);
  }

  private boolean checkIfUserHasAccess(Set<LdapGroup> ldapGroups) {
    return !ldapGroups.isEmpty() &&
        !Sets.intersection(
            settings.getGroups(),
            ldapGroups.stream().map(LdapGroup::getName).collect(Collectors.toSet())
        ).isEmpty();
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}
