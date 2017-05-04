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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.LdapConfigs;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.ldap.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthorizationAsyncRule extends AsyncAuthorization {

  private static final String RULE_NAME = "ldap_authorization";

  private static final String LDAP_NAME = "name";
  private static final String LDAP_GROUP_NAMES = "groups";

  private final GroupsProviderLdapClient client;
  private final Set<String> groups;

  private LdapAuthorizationAsyncRule(GroupsProviderLdapClient client, Set<String> groups, ESContext context) {
    super(context);
    this.client = client;
    this.groups = groups;
  }

  public static Optional<LdapAuthorizationAsyncRule> fromSettings(Settings s, LdapConfigs ldapConfigs, ESContext context)
      throws ConfigMalformedException {
    return fromSettings(RULE_NAME, s, context, ldapConfigs);
  }

  static Optional<LdapAuthorizationAsyncRule> fromSettings(String ruleName, Settings s, ESContext context,
      LdapConfigs ldapConfigs) throws ConfigMalformedException {
    Settings ldapSettings = s.getAsSettings(ruleName);
    if(ldapSettings.isEmpty()) return Optional.empty();

    String name = ldapSettings.get(LDAP_NAME);
    if (name == null)
      throw new ConfigMalformedException("No [" + LDAP_NAME + "] attribute defined");
    Set<String> groups = Sets.newHashSet(ldapSettings.getAsArray(LDAP_GROUP_NAMES));

    return Optional.of(new LdapAuthorizationAsyncRule(ldapConfigs.authorizationLdapClientForName(name), groups, context));
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
    return ldapGroups.isEmpty() ||
        !Sets.intersection(
            groups,
            ldapGroups.stream().map(LdapGroup::getName).collect(Collectors.toSet())
        ).isEmpty();
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

  public GroupsProviderLdapClient getClient() {
    return client;
  }
}
