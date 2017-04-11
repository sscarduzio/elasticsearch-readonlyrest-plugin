package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;
import org.junit.Test;

import java.util.Base64;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.utils.mocks.RequestContextMock.mockedRequestContext;
import static org.elasticsearch.plugin.readonlyrest.utils.settings.LdapConfigHelper.mockLdapConfig;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LdapAuthenticationAsyncRuleTests {

  @Test
  public void testRuleSuccessfulCreationFromSettings() {
    LdapConfig config1 = mockLdapConfig("ldap1");
    LdapConfig config2 = mockLdapConfig("ldap2");
    Settings blockSettings = Settings.builder()
        .put("ldap_authentication", "ldap1")
        .build();

    Optional<LdapAuthenticationAsyncRule> rule =
        LdapAuthenticationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
    assertEquals(true, rule.isPresent());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleCreationFromSettingsFailsDueToNotFoundLdapWithGivenName() {
    LdapConfig config1 = mockLdapConfig("ldap1");
    LdapConfig config2 = mockLdapConfig("ldap2");
    Settings blockSettings = Settings.builder()
        .put("ldap_authentication", "ldap3")
        .build();

    LdapAuthenticationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2));
  }

  @Test
  public void testUserShouldBeNotAuthenticatedIfLdapReturnError() throws Exception {
    LdapConfig config1 = mockLdapConfig("ldap1", Optional.empty());
    LdapConfig config2 = mockLdapConfig("ldap2", Optional.empty());
    Settings blockSettings = Settings.builder()
        .put("ldap_authentication", "ldap1")
        .build();

    LdapAuthenticationAsyncRule rule =
        LdapAuthenticationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

  @Test
  public void testUserShouldBeAuthenticatedIfLdapReturnSuccess() throws Exception {
    LdapConfig config1 = mockLdapConfig("ldap1", Optional.empty());
    LdapConfig config2 = mockLdapConfig("ldap2", Optional.of(new Tuple<>(
        new LdapUser(
            "user",
            "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group2"))
    )));
    Settings blockSettings = Settings.builder()
        .put("ldap_authentication", "ldap2")
        .build();

    LdapAuthenticationAsyncRule rule =
        LdapAuthenticationAsyncRule.fromSettings(blockSettings, Lists.newArrayList(config1, config2)).get();
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(true, match.isMatch());
  }

}
