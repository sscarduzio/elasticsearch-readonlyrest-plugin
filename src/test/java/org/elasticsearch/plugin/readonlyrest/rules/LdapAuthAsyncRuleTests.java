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

package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;
import org.junit.Test;

import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class LdapAuthAsyncRuleTests {

    @Test
    public void testRuleSuccessfulCreationFromSettings() {
        LdapConfig config1 = mockLdapConfig("ldap1");
        LdapConfig config2 = mockLdapConfig("ldap2");
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap2")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        Optional<LdapAuthAsyncRule> rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
        assertEquals(true, rule.isPresent());
    }

    @Test
    public void testRuleNotCreatedDueToNoLdapAuthSettingKey() {
        Settings blockSettings = baseExampleBlockBuilder().build();
        Optional<LdapAuthAsyncRule> rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList());
        assertEquals(false, rule.isPresent());
    }

    @Test(expected = ConfigMalformedException.class)
    public void testRuleCreationFromSettingsFailsDueToNotFoundLdapWithGivenName() {
        LdapConfig config1 = mockLdapConfig("ldap1");
        LdapConfig config2 = mockLdapConfig("ldap2");
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap3")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
    }

    @Test(expected = ConfigMalformedException.class)
    public void testRuleCreationFromSettingsFailsDueToNotSetLdapAuthName() {
        LdapConfig config1 = mockLdapConfig("ldap1");
        LdapConfig config2 = mockLdapConfig("ldap2");
        Settings blockSettings = baseExampleBlockBuilder()
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap3")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
    }

    @Test
    public void testUserShouldBeNotAuthenticatedIfAllLdapsReturnError() throws Exception {
        LdapConfig config1 = mockLdapConfig("ldap1", Optional.empty());
        LdapConfig config2 = mockLdapConfig("ldap2", Optional.empty());
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap2")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
        RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
        assertEquals(false, match.isMatch());
    }

    @Test
    public void testUserShouldBeAuthenticatedIfAnyOfLdapsReturnSuccess() throws Exception {
        LdapConfig config1 = mockLdapConfig("ldap1", Optional.empty());
        LdapConfig config2 = mockLdapConfig("ldap2", Optional.of(
                new LdapUser(
                        "user",
                        "cn=Example user,ou=People,dc=example,dc=com",
                        Sets.newHashSet(new LdapGroup("group2")))));
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap2")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
        RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
        assertEquals(true, match.isMatch());
    }

    @Test
    public void testUserShouldNotBeAuthenticatedIfLdapHasAGivenUserButWithinDifferentGroup() throws Exception {
        LdapConfig config1 = mockLdapConfig("ldap1", Optional.of(
                new LdapUser(
                        "user",
                        "cn=Example user,ou=People,dc=example,dc=com",
                        Sets.newHashSet(new LdapGroup("group5")))));
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1)).get();
        RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
        assertEquals(false, match.isMatch());
    }

    @Test
    public void testUserShouldNotAuthenticateWithSecondLdapIfFirstReturnedSucccess() throws Exception {
        LdapConfig config1 = mockLdapConfig("ldap1", Optional.of(
                new LdapUser(
                        "user",
                        "cn=Example user,ou=People,dc=example,dc=com",
                        Sets.newHashSet(new LdapGroup("group1")))));
        LdapConfig config2 = mockLdapConfig("ldap2", Optional.empty());
        Settings blockSettings = baseExampleBlockBuilder()
                .put("ldap_auth.0.name", "ldap1")
                .putArray("ldap_auth.0.groups", Lists.newArrayList("group1", "group2"))
                .put("ldap_auth.1.name", "ldap2")
                .putArray("ldap_auth.1.groups", Lists.newArrayList("group2", "group3"))
                .build();

        LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
        RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
        assertEquals(true, match.isMatch());
        verify(config1.getClient(), times(1)).authenticate(any(LdapCredentials.class));
        verify(config2.getClient(), times(0)).authenticate(any(LdapCredentials.class));
    }

    private Settings.Builder baseExampleBlockBuilder() {
        return Settings.builder()
                .put("name", "Accept requests from users in group team2 on blog2")
                .put("type", "allow")
                .putArray("indices", Lists.newArrayList("blog"));
    }

    private LdapConfig mockLdapConfig(String name) {
        return mockLdapConfig(name, Optional.empty());
    }

    private LdapConfig mockLdapConfig(String name, Optional<LdapUser> onAuthenticate) {
        LdapConfig config = mock(LdapConfig.class);
        when(config.getName()).thenReturn(name);
        LdapClient client = mock(LdapClient.class);
        when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(onAuthenticate));
        when(config.getClient()).thenReturn(client);
        return config;
    }

    private RequestContext mockedRequestContext(String user, String pass) {
        RequestContext mock = mock(RequestContext.class);
        when(mock.getHeaders()).thenReturn(
                Maps.newHashMap(ImmutableMap.<String, String>builder()
                        .put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes()))
                        .build()));
        return mock;
    }
}
