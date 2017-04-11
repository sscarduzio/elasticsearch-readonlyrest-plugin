package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;
import org.junit.Test;

import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.utils.mocks.RequestContextMock.mockedRequestContext;
import static org.elasticsearch.plugin.readonlyrest.utils.settings.LdapConfigHelper.mockLdapConfig;
import static org.junit.Assert.assertEquals;

public class LdapAuthorizationAsyncRuleTests {

  @Test
  public void testRuleSuccessfulCreationFromSettings() {
    LdapConfig config1 = mockLdapConfig("ldap1");
    LdapConfig config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_authorization.0.name", "ldap1")
        .putArray("ldap_authorization.0.groups", Lists.newArrayList("group1", "group2"))
        .build();

    Optional<LdapAuthorizationAsyncRule> rule =
        LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
    assertEquals(true, rule.isPresent());
  }

  @Test
  public void testRuleNotCreatedDueToNoLdapAuthSettingKey() {
    Settings blockSettings = baseExampleBlockBuilder().build();
    Optional<LdapAuthorizationAsyncRule> rule =
        LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList());
    assertEquals(false, rule.isPresent());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleCreationFromSettingsFailsDueToNotFoundLdapWithGivenName() {
    LdapConfig config1 = mockLdapConfig("ldap1");
    LdapConfig config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_authorization.0.name", "ldap3")
        .putArray("ldap_authorization.0.groups", Lists.newArrayList("group2", "group3"))
        .build();

    LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleCreationFromSettingsFailsDueToNotSetLdapAuthName() {
    LdapConfig config1 = mockLdapConfig("ldap1");
    LdapConfig config2 = mockLdapConfig("ldap2");
    Settings blockSettings = baseExampleBlockBuilder()
        .putArray("ldap_authorization.0.groups", Lists.newArrayList("group1", "group2"))
        .build();

    LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
  }


  @Test
  public void testUserShouldBeAuthorizedIfLdapReturnSuccess() throws Exception {
    LdapConfig config1 = mockLdapConfig("ldap1", Optional.empty());
    LdapConfig config2 = mockLdapConfig("ldap2", Optional.of(new Tuple<>(
        new LdapUser(
            "user",
            "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group2"))
    )));
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_authorization.0.name", "ldap2")
        .putArray("ldap_authorization.0.groups", Lists.newArrayList("group1", "group2"))
        .build();

    LdapAuthorizationAsyncRule rule = LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(true, match.isMatch());
  }

  @Test
  public void testUserShouldNotBeAuthorizedIfLdapHasAGivenUserButWithinDifferentGroup() throws Exception {
    LdapConfig config1 = mockLdapConfig("ldap1", Optional.of(new Tuple<>(
        new LdapUser(
            "user",
            "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group5"))
    )));
    Settings blockSettings = baseExampleBlockBuilder()
        .put("ldap_authorization.0.name", "ldap1")
        .putArray("ldap_authorization.0.groups", Lists.newArrayList("group2", "group3"))
        .build();

    LdapAuthorizationAsyncRule rule = LdapAuthorizationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1)).get();
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
