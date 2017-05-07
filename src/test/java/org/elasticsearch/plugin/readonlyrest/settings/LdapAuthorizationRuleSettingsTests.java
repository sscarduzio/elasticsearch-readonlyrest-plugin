package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.testutils.settings.MockLdapClientHelper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LdapAuthorizationRuleSettingsTests {

  @Test
  public void testRuleSettingsSuccessfulCreation() {
    LdapAuthorizationRuleSettings ruleSettings = LdapAuthorizationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authorization:\n" +
            "  name: ldap1\n" +
            "  groups: [group1, group2]")
            .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_authorization", ruleSettings.getName());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotFoundLdapWithGivenName() {
    LdapAuthorizationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authorization:\n" +
            "  name: ldap3\n" +
            "  groups: [group2, group3]")
            .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotSetLdapAuthName() {
    LdapAuthorizationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authorization:\n" +
            "  groups: [group1, group2]")
            .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToEmptyGroupsSet() {
    LdapAuthorizationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authorization:\n" +
            "  name: ldap1\n" +
            "  groups: []")
            .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }
}
