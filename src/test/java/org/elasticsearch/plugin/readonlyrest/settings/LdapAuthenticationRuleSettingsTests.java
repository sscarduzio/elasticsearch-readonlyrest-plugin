package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.settings.MockLdapClientHelper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LdapAuthenticationRuleSettingsTests {

  @Test
  public void testRuleSettginsSuccessfulCreation() {
    LdapAuthenticationRuleSettings settings = LdapAuthenticationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authentication:\n" +
            "  name: ldap1")
            .inner(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_authentication", settings.getName());
  }

  @Test
  public void testRuleSettingsSuccessfulCreationFromShortenedVersion() {
    LdapAuthenticationRuleSettings settings = LdapAuthenticationRuleSettings.from(
        RawSettings.fromString("ldap_authentication: ldap1")
            .stringReq(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_authentication", settings.getName());
  }

  @Test(expected = ConfigMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotFoundLdapWithGivenName() {
    LdapAuthenticationRuleSettings.from(
        RawSettings.fromString("" +
            "ldap_authentication:\n" +
            "  name: ldap3")
            .inner(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

}
