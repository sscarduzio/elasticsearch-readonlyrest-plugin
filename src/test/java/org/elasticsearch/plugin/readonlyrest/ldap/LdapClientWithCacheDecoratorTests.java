package org.elasticsearch.plugin.readonlyrest.ldap;

import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.junit.Assert.assertEquals;

public class LdapClientWithCacheDecoratorTests {

    @Test
    public void testIfAuthenticatedUserIsCachedByGivenDuration() throws Exception {
        LdapClient client = Mockito.mock(LdapClient.class);
        String dn = "cn=Example user,ou=People,dc=example,dc=com";
        LdapCredentials credentials = new LdapCredentials("user", "password");
        when(client.authenticate(any(LdapCredentials.class)))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(
                        new LdapUser("user", dn)))
                );
        Duration ttl = Duration.ofSeconds(1);
        LdapClientWithCacheDecorator clientWithCache = new LdapClientWithCacheDecorator(client, ttl);
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
