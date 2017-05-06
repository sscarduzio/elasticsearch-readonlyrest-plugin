package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.settings.MockLdapClientHelper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LdapAuthRuleSettingsTests {

  @Test
  public void testRuleSettingsSuccessfulCreation() {
    LdapAuthRuleSettings settings = LdapAuthRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_auth:\n" +
            "  name: ldap1\n" +
            "  groups: [group1, group2]")
            .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_auth", settings.getName());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotFoundLdapWithGivenName() {
    LdapAuthRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_auth:\n" +
            "  name: ldap3\n" +
            "  groups: [group1, group2]")
            .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotSetLdapAuthName() {
    LdapAuthRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_auth:\n" +
            "  groups: [group1, group2]")
            .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToEmptyGroupsSet() {
    LdapAuthRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_auth:\n" +
            "  name: ldap1\n" +
            "  groups: []")
            .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }
}
