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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.ldap.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.BaseLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.GroupsProviderLdapClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LdapConfigs {

  private final Map<String, BaseLdapClient> configs;

  private LdapConfigs(List<LdapConfig<?>> configs) {
    this.configs = configs.stream()
      .collect(Collectors.toMap(LdapConfig::getName, LdapConfig::getClient));
  }

  public static LdapConfigs fromSettings(String name, Settings settings) {
    return new LdapConfigs(
      settings.getGroups(name).values()
        .stream()
        .map(LdapConfig::fromSettings)
        .collect(Collectors.toList())
    );
  }

  public static LdapConfigs empty() {
    return new LdapConfigs(Lists.newArrayList());
  }

  public static LdapConfigs from(LdapConfig<?>... configsList) {
    return new LdapConfigs(Lists.newArrayList(configsList));
  }

  @SuppressWarnings("unchecked")
  public AuthenticationLdapClient authenticationLdapClientForName(String name) {
    if (!configs.containsKey(name)) {
      throw new ConfigMalformedException("LDAP with name [" + name + "] wasn't defined.");
    }
    BaseLdapClient client = configs.get(name);
    if (client instanceof AuthenticationLdapClient) {
      return (AuthenticationLdapClient) client;
    }
    throw new ConfigMalformedException("Cannot use LDAP with name [" + name + "] to authentication");
  }

  @SuppressWarnings("unchecked")
  public GroupsProviderLdapClient authorizationLdapClientForName(String name) {
    if (!configs.containsKey(name)) {
      throw new ConfigMalformedException("LDAP with name [" + name + "] wasn't defined.");
    }
    BaseLdapClient client = configs.get(name);
    if (client instanceof GroupsProviderLdapClient) {
      return (GroupsProviderLdapClient) client;
    }
    throw new ConfigMalformedException("Cannot use LDAP with name [" + name + "] to authorization");
  }
}
