package org.elasticsearch.plugin.readonlyrest.ldap;

import org.elasticsearch.plugin.readonlyrest.utils.LdapContainer;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class UnboundidLdapClientTests {

    @ClassRule
    public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

    private LdapClient client = new UnboundidLdapClient(
            ldapContainer.getLdapHost(),
            ldapContainer.getLdapPort(),
            Optional.of(ldapContainer.getBindDNAndPassword()),
            "ou=People,dc=example,dc=com",
            "ou=Groups,dc=example,dc=com",
            10,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            false,
            false
    );

    @Test
    public void testAuthenticationSuccess() throws Exception {
        CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
                new LdapCredentials("cartman", "user2")
        );
        Optional<LdapUser> user = userF.get();
        Assert.assertEquals(user.isPresent(), true);
    }

    @Test
    public void testAuthenticationErrorDueToInvalidPassword() throws Exception {
        CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
                new LdapCredentials("cartman", "wrongpassword")
        );
        Optional<LdapUser> user = userF.get();
        Assert.assertEquals(user.isPresent(), false);
    }

    @Test
    public void testAuthenticationErrorDueToUnknownUser() throws Exception {
        CompletableFuture<Optional<LdapUser>> userF = client.authenticate(
                new LdapCredentials("nonexistent", "whatever")
        );
        Optional<LdapUser> user = userF.get();
        Assert.assertEquals(user.isPresent(), false);
    }
}
