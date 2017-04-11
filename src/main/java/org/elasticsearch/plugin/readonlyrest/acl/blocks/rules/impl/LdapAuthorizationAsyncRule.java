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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthorizationAsyncRule extends AsyncAuthorization {

  private static final String RULE_NAME = "ldap_authorization";

  private static final String LDAP_NAME = "name";
  private static final String LDAP_GROUP_NAMES = "groups";

  private final LdapClient client;
  private final Set<String> groups;

  public static Optional<LdapAuthorizationAsyncRule> fromSettings(Settings s,
                                                                  List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
    return fromSettings(RULE_NAME, s, ldapConfigs);
  }

  static Optional<LdapAuthorizationAsyncRule> fromSettings(String ruleName, Settings s,
                                                           List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
    Map<String, Settings> ldapAuths = s.getGroups(ruleName);
    if (ldapAuths.isEmpty()) return Optional.empty();
    if (ldapAuths.size() != 1)
      throw new ConfigMalformedException("Only one [" + ruleName + "] in group could be defined");

    Map<String, LdapClient> ldapClientsByName = ldapConfigs.stream()
        .collect(Collectors.toMap(LdapConfig::getName, LdapConfig::getClient));

    Settings ldapSettings = Lists.newArrayList(ldapAuths.values()).get(0);
    String name = ldapSettings.get(LDAP_NAME);
    if (name == null)
      throw new ConfigMalformedException("No [" + LDAP_NAME + "] attribute defined");
    Set<String> groups = Sets.newHashSet(ldapSettings.getAsArray(LDAP_GROUP_NAMES));
    if (!ldapClientsByName.containsKey(name)) {
      throw new ConfigMalformedException("LDAP with name [" + name + "] wasn't defined.");
    }
    return Optional.of(new LdapAuthorizationAsyncRule(ldapClientsByName.get(name), groups));
  }

  private LdapAuthorizationAsyncRule(LdapClient client, Set<String> groups) {
    this.client = client;
    this.groups = groups;
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    return client.userById(user.getId())
        .thenCompose(ldapUser -> ldapUser.isPresent()
            ? client.userGroups(ldapUser.get())
            : CompletableFuture.completedFuture(Sets.newHashSet())
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

  public LdapClient getClient() {
    return client;
  }
}
