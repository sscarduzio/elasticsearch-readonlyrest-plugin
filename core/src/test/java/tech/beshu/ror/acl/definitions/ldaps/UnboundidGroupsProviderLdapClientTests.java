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

package tech.beshu.ror.acl.definitions.ldaps;

import tech.beshu.ror.acl.definitions.ldaps.unboundid.ConnectionConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.SearchingUserConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UnboundidGroupsProviderLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UserGroupsSearchFilterConfig;
import tech.beshu.ror.acl.definitions.ldaps.unboundid.UserSearchFilterConfig;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.utils.containers.LdapContainer;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class UnboundidGroupsProviderLdapClientTests {

  @ClassRule
  public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

  private ConnectionConfig connectionConfig = new ConnectionConfig.Builder(ldapContainer.getLdapHost())
    .setPort(ldapContainer.getLdapPort())
    .setPoolSize(10)
    .setConnectionTimeout(Duration.ofSeconds(1))
    .setRequestTimeout(Duration.ofSeconds(1))
    .setSslEnabled(false)
    .setTrustAllCerts(false)
    .build();

  private UserSearchFilterConfig.Builder userSearchFilterConfigBuilder =
      new UserSearchFilterConfig.Builder("ou=People,dc=example,dc=com");

  private UserGroupsSearchFilterConfig.Builder userGroupsSearchFilterConfigBuilder =
      new UserGroupsSearchFilterConfig.Builder("ou=Groups,dc=example,dc=com");

  private Optional<SearchingUserConfig> searchingUserConfig = Optional.of(ldapContainer.getSearchingUserConfig()).map(t -> new SearchingUserConfig(t.v1(), t.v2()));

  private UnboundidGroupsProviderLdapClient client = new UnboundidGroupsProviderLdapClient(
      connectionConfig,
      userSearchFilterConfigBuilder.build(),
      userGroupsSearchFilterConfigBuilder.build(),
      searchingUserConfig,
      MockedESContext.INSTANCE
  );

  private UnboundidGroupsProviderLdapClient clientGroupsFromUser = new UnboundidGroupsProviderLdapClient(
      connectionConfig,
      userSearchFilterConfigBuilder.build(),
      userGroupsSearchFilterConfigBuilder.setIsGroupsFromUser(true).build(),
      searchingUserConfig,
      MockedESContext.INSTANCE
  );

  @Test
  public void testUserGroupsFetching() throws Exception {
    Assert.assertTrue(doTestUserGroupFetching(client));
  }

  @Test
  public void testUserGroupsFromUserFetching() throws Exception {
    Assert.assertTrue(doTestUserGroupFetching(clientGroupsFromUser));
  }

  private boolean doTestUserGroupFetching(UnboundidGroupsProviderLdapClient client) throws Exception {
    CompletableFuture<Set<LdapGroup>> cartmanGroupsF = client.userGroups(
      new LdapUser("cartman", "cn=Eric Cartman,ou=People,dc=example,dc=com")
    );
    Set<LdapGroup> cartmanGroups = cartmanGroupsF.get();
    return cartmanGroups.size() == 2;
  }

  @Test
  public void testEmptyUserGroupsFetching() throws Exception {
    Assert.assertTrue(doTestEmptyGroupFetching(client));
  }

  @Test
  public void testEmptyUserGroupsFromUserFetching() throws Exception {
    Assert.assertTrue(doTestEmptyGroupFetching(clientGroupsFromUser));
  }

  private boolean doTestEmptyGroupFetching(UnboundidGroupsProviderLdapClient client) throws Exception {
    CompletableFuture<Set<LdapGroup>> guserGroupsF = client.userGroups(
        new LdapUser("guser", "cn=Groupless User,ou=People,dc=example,dc=com")
    );
    Set<LdapGroup> guserGroups = guserGroupsF.get();
    return guserGroups.size() == 0;
  }

}
