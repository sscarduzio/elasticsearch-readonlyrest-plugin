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

    private LdapClient client = new UnboundidLdapClient.Builder(
            ldapContainer.getLdapHost(),
            "ou=People,dc=example,dc=com",
            "ou=Groups,dc=example,dc=com")
            .setPort(ldapContainer.getLdapPort())
            .setBindDnPassword(ldapContainer.getBindDNAndPassword())
            .setPoolSize(10)
            .setConnectionTimeout(Duration.ofSeconds(1))
            .setRequestTimeout(Duration.ofSeconds(1))
            .setSslEnabled(false)
            .setTrustAllCerts(false)
            .build();

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
