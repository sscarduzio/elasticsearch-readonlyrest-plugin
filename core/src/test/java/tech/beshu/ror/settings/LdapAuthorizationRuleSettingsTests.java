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
package tech.beshu.ror.settings;

import tech.beshu.ror.TestUtils;
import tech.beshu.ror.commons.SettingsMalformedException;
import tech.beshu.ror.mocks.MockLdapClientHelper;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LdapAuthorizationRuleSettingsTests {

  @Test
  public void testRuleSettingsSuccessfulCreation() {
    LdapAuthorizationRuleSettings ruleSettings = LdapAuthorizationRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_authorization:\n" +
                                 "  name: ldap1\n" +
                                 "  groups: [group1, group2]")
        .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_authorization", ruleSettings.getName());
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotFoundLdapWithGivenName() {
    LdapAuthorizationRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_authorization:\n" +
                                 "  name: ldap3\n" +
                                 "  groups: [group2, group3]")
        .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotSetLdapAuthName() {
    LdapAuthorizationRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_authorization:\n" +
                                 "  groups: [group1, group2]")
        .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToEmptyGroupsSet() {
    LdapAuthorizationRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_authorization:\n" +
                                 "  name: ldap1\n" +
                                 "  groups: []")
        .inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }
}
