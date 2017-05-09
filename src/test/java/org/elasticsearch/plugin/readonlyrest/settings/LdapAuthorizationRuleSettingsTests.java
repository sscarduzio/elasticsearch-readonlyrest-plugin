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
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.settings.MockLdapClientHelper;
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
