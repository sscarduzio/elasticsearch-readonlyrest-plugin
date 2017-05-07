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
package org.elasticsearch.plugin.readonlyrest.testutils.settings;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.AuthenticationLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.testutils.Tuple;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MockLdapClientHelper {

  public static LdapClientFactory simpleFactory(GroupsProviderLdapClient client) {
    return new LdapClientFactory() {
      @Override
      public GroupsProviderLdapClient getClient(GroupsProviderLdapSettings settings) {
        return client;
      }
      @Override
      public AuthenticationLdapClient getClient(AuthenticationLdapSettings settings) {
        return client;
      }
    };
  }

  public static LdapSettings mockLdapSettings() {
    return mock(LdapSettings.class);
  }

  public static GroupsProviderLdapClient mockLdapClient() {
    return mockLdapClient(Optional.empty());
  }

  public static GroupsProviderLdapClient mockLdapClient(LdapUser user, Set<LdapGroup> groups) {
    return mockLdapClient(Optional.of(new Tuple<>(user, groups)));
  }

  @SuppressWarnings("unchecked")
  private static GroupsProviderLdapClient mockLdapClient(Optional<Tuple<LdapUser, Set<LdapGroup>>> onAuthenticate) {
    GroupsProviderLdapClient client = mock(GroupsProviderLdapClient.class);
    if (onAuthenticate.isPresent()) {
      LdapUser user = onAuthenticate.map(Tuple::v1).get();
      Set<LdapGroup> groups = onAuthenticate.map(Tuple::v2).get();
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
      when(client.userGroups(user)).thenReturn(CompletableFuture.completedFuture(groups));
      when(client.userById(user.getUid())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
    } else {
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
      when(client.userGroups(any())).thenReturn(CompletableFuture.completedFuture(Sets.newHashSet()));
      when(client.userById(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }
    return client;
  }

  public static RawSettings mockLdapsCollection() {
    return RawSettings.fromString("" +
        "ldaps:\n" +
        " - name: ldap1\n" +
        "   host: \"localhost\"\n" +
        "   port: 389                                                 # default 389\n" +
        "   ssl_enabled: false                                        # default true\n" +
        "   ssl_trust_all_certs: true                                 # default false\n" +
        "   bind_dn: \"cn=admin,dc=example,dc=com\"                     # skip for anonymous bind\n" +
        "   bind_password: \"password\"                                 # skip for anonymous bind\n" +
        "   search_user_base_DN: \"ou=People,dc=example,dc=com\"\n" +
        "   search_groups_base_DN: \"ou=Groups,dc=example,dc=com\"\n" +
        "   user_id_attribute: \"uid\"                                  # default \"uid\"\n" +
        "   unique_member_attribute: \"uniqueMember\"                   # default \"uniqueMember\"\n" +
        "   connection_pool_size: 10                                  # default 30\n" +
        "   connection_timeout_in_sec: 10                             # default 1\n" +
        "   request_timeout_in_sec: 10                                # default 1\n" +
        "   cache_ttl_in_sec: 60\n" +
        "\n" +
        " - name: ldap2\n" +
        "   host: \"localhost\"\n" +
        "   port: 389                                                 # default 389\n" +
        "   ssl_enabled: false                                        # default true\n" +
        "   ssl_trust_all_certs: true                                 # default false\n" +
        "   bind_dn: \"cn=admin,dc=example,dc=com\"                     # skip for anonymous bind\n" +
        "   bind_password: \"password\"                                 # skip for anonymous bind\n" +
        "   search_user_base_DN: \"ou=People,dc=example,dc=com\"\n" +
        "   search_groups_base_DN: \"ou=Groups,dc=example,dc=com\"\n" +
        "   user_id_attribute: \"uid\"                                  # default \"uid\"\n" +
        "   unique_member_attribute: \"uniqueMember\"                   # default \"uniqueMember\"\n" +
        "   connection_pool_size: 10                                  # default 30\n" +
        "   connection_timeout_in_sec: 10                             # default 1\n" +
        "   request_timeout_in_sec: 10                                # default 1\n" +
        "   cache_ttl_in_sec: 60 ");
  }
}
