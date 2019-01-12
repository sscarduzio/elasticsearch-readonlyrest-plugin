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

package tech.beshu.ror.unit.acl.definitions.ldaps;

import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.unit.acl.definitions.ldaps.caching.GroupsProviderLdapClientCacheDecorator;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LdapClientWithCacheDecoratorTests {

  @Test
  public void testIfAuthenticatedUserIsCachedByGivenDuration() throws Exception {
    GroupsProviderLdapClient client = Mockito.mock(GroupsProviderLdapClient.class);
    String dn = "cn=Example user,ou=People,dc=example,dc=com";
    LdapCredentials credentials = new LdapCredentials("user", "password");
    when(client.authenticate(any(LdapCredentials.class)))
      .thenReturn(CompletableFuture.completedFuture(Optional.of(
        new LdapUser("user", dn)))
      );
    Duration ttl = Duration.ofSeconds(1);
    GroupsProviderLdapClientCacheDecorator clientWithCache = new GroupsProviderLdapClientCacheDecorator(client, ttl);
    Optional<LdapUser> ldapUser = clientWithCache.authenticate(credentials).get();
    assertEquals(ldapUser.get().getDN(), dn);
    Optional<LdapUser> ldapUserSecondAttempt = clientWithCache.authenticate(credentials).get();
    assertEquals(ldapUserSecondAttempt.get().getDN(), dn);
    Thread.sleep((long) (ttl.toMillis() * 1.5));
    Optional<LdapUser> ldapUserThirdAttempt = clientWithCache.authenticate(credentials).get();
    assertEquals(ldapUserThirdAttempt.get().getDN(), dn);
    verify(client, times(2)).authenticate(credentials);
  }

}
