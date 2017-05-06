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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.LdapConfigs;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.Test;

import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.utils.mocks.RequestContextMock.mockedRequestContext;
import static org.elasticsearch.plugin.readonlyrest.utils.settings.LdapConfigHelper.mockLdapConfig;
import static org.junit.Assert.assertEquals;

public class LdapAuthAsyncRuleTests {

  @Test
  public void testRuleSuccessfulCreationFromSettings() {
    LdapConfig<?> config1 = mockLdapConfig("ldap1");
    LdapConfig<?> config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_auth.name", "ldap1")
        .putArray("ldap_auth.groups", Lists.newArrayList("group1", "group2"))
        .build();

    Optional<LdapAuthAsyncRule> rule = LdapAuthAsyncRule.fromSettings(blockSettings,
        LdapConfigs.from(config1, config2), MockedESContext.INSTANCE);
    assertEquals(true, rule.isPresent());
  }

  @Test
  public void testRuleNotCreatedDueToNoLdapAuthSettingKey() {
    Settings blockSettings = baseExampleBlockBuilder().build();
    Optional<LdapAuthAsyncRule> rule = LdapAuthAsyncRule.fromSettings(blockSettings,
        LdapConfigs.empty(), MockedESContext.INSTANCE);
    assertEquals(false, rule.isPresent());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleCreationFromSettingsFailsDueToNotFoundLdapWithGivenName() {
    LdapConfig<?> config1 = mockLdapConfig("ldap1");
    LdapConfig<?> config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_auth.name", "ldap3")
        .putArray("ldap_auth.groups", Lists.newArrayList("group2", "group3"))
        .build();

    LdapAuthAsyncRule.fromSettings(blockSettings, LdapConfigs.from(config1, config2), MockedESContext.INSTANCE);
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleCreationFromSettingsFailsDueToNotSetLdapAuthName() {
    LdapConfig<?> config1 = mockLdapConfig("ldap1");
    LdapConfig<?> config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .putArray("ldap_auth.groups", Lists.newArrayList("group1", "group2"))
        .build();

    LdapAuthAsyncRule.fromSettings(blockSettings, LdapConfigs.from(config1, config2), MockedESContext.INSTANCE);
  }

  @Test
  public void testUserShouldBeNotAuthenticatedIfLdapReturnError() throws Exception {
    LdapConfig<?> config1 = mockLdapConfig("ldap1", Optional.empty());
    LdapConfig<?> config2 = mockLdapConfig("ldap2", Optional.empty());
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_auth.name", "ldap1")
        .putArray("ldap_auth.groups", Lists.newArrayList("group1", "group2"))
        .build();

    LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings,
        LdapConfigs.from(config1, config2), MockedESContext.INSTANCE).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

  @Test
  public void testUserShouldBeAuthenticatedIfLdapReturnSuccess() throws Exception {
    LdapConfig<?> config1 = mockLdapConfig("ldap1", Optional.empty());
    LdapConfig<?> config2 = mockLdapConfig("ldap2", Optional.of(new Tuple<>(
        new LdapUser(
            "user",
            "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group2"))
    )));
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_auth.name", "ldap2")
        .putArray("ldap_auth.groups", Lists.newArrayList("group1", "group2"))
        .build();

    LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings,
        LdapConfigs.from(config1, config2), MockedESContext.INSTANCE).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(true, match.isMatch());
  }

  @Test
  public void testUserShouldNotBeAuthorizedIfLdapHasAGivenUserButWithinDifferentGroup() throws Exception {
    LdapConfig<?> config1 = mockLdapConfig("ldap1", Optional.of(new Tuple<>(
        new LdapUser(
            "user",
            "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group5"))
    )));
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_auth.name", "ldap1")
        .putArray("ldap_auth.groups", Lists.newArrayList("group2", "group3"))
        .build();

    LdapAuthAsyncRule rule = LdapAuthAsyncRule.fromSettings(blockSettings,
        LdapConfigs.from(config1), MockedESContext.INSTANCE).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

  private Settings.Builder baseExampleBlockBuilder() {
    return Settings.builder()
                   .put("name", "Accept requests from users in group team2 on blog2")
                   .put("type", "allow")
                   .putArray("indices", Lists.newArrayList("blog"));
  }

}
