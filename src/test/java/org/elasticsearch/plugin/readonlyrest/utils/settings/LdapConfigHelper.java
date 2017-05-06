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
package org.elasticsearch.plugin.readonlyrest.utils.settings;

import com.google.common.collect.Sets;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LdapConfigHelper {

  public static LdapConfig<?> mockLdapConfig(String name) {
    return mockLdapConfig(name, Optional.empty());
  }

  @SuppressWarnings("unchecked")
  public static LdapConfig<?> mockLdapConfig(String name, Optional<Tuple<LdapUser, Set<LdapGroup>>> onAuthenticate) {
    LdapConfig<GroupsProviderLdapClient> config = (LdapConfig<GroupsProviderLdapClient>) mock(LdapConfig.class);
    when(config.getName()).thenReturn(name);
    GroupsProviderLdapClient client = mock(GroupsProviderLdapClient.class);
    if (onAuthenticate.isPresent()) {
      LdapUser user = onAuthenticate.map(Tuple::v1).get();
      Set<LdapGroup> groups = onAuthenticate.map(Tuple::v2).get();
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
      when(client.userGroups(user)).thenReturn(CompletableFuture.completedFuture(groups));
      when(client.userById(user.getUid())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
    }
    else {
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
      when(client.userGroups(any())).thenReturn(CompletableFuture.completedFuture(Sets.newHashSet()));
      when(client.userById(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }
    when(config.getClient()).thenReturn(client);
    return config;
  }
}
