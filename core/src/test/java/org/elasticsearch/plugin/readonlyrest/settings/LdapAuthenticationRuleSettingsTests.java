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
package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.mocks.MockLdapClientHelper;
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

  @Test(expected = SettingsMalformedException.class)
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
