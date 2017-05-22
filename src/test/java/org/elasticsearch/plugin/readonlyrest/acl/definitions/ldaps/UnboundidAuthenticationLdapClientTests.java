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

package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.ConnectionConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UnboundidGroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserGroupsSearchFilterConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid.UserSearchFilterConfig;
import org.elasticsearch.plugin.readonlyrest.utils.containers.LdapContainer;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class UnboundidAuthenticationLdapClientTests {

  @ClassRule
  public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

  private UnboundidGroupsProviderLdapClient client = new UnboundidGroupsProviderLdapClient(
      new ConnectionConfig.Builder(ldapContainer.getLdapHost())
          .setPort(ldapContainer.getLdapPort())
          .setPoolSize(10)
          .setConnectionTimeout(Duration.ofSeconds(1))
          .setRequestTimeout(Duration.ofSeconds(1))
          .setSslEnabled(false)
          .setTrustAllCerts(false)
          .build(),
      new UserSearchFilterConfig.Builder("ou=People,dc=example,dc=com").build(),
      new UserGroupsSearchFilterConfig.Builder("ou=Groups,dc=example,dc=com").build(),
      Optional.of(ldapContainer.getSearchingUserConfig()),
      MockedESContext.INSTANCE
  );

  @Test
  public void testAuthenticationSuccess() throws Exception {
    CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
        new LdapCredentials("cartman", "user2")
    );
    Optional<LdapUser> user = userF.get();
    Assert.assertEquals(true, user.isPresent());
  }

  @Test
  public void testAuthenticationErrorDueToInvalidPassword() throws Exception {
    CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
        new LdapCredentials("cartman", "wrongpassword")
    );
    Optional<LdapUser> user = userF.get();
    Assert.assertEquals(false, user.isPresent());
  }

  @Test
  public void testAuthenticationErrorDueToUnknownUser() throws Exception {
    CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
        new LdapCredentials("nonexistent", "whatever")
    );
    Optional<LdapUser> user = userF.get();
    Assert.assertEquals(false, user.isPresent());
  }

  @Test
  public void testAuthenticationSuccessAndCNIsProperlyEscaped() throws Exception {
    CompletableFuture<Set<LdapGroup>> groupsF = client.authenticate(new LdapCredentials("viktor", "user2"))
        .thenCompose(ldapUser ->
            ldapUser.map(user -> client.userGroups(user))
                .orElse(CompletableFuture.completedFuture(Sets.newHashSet()))
        );
    Assert.assertEquals(false, groupsF.get().isEmpty());
  }
}
