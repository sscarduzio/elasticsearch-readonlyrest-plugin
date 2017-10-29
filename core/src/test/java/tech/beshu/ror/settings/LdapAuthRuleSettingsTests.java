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

import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.mocks.MockLdapClientHelper;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;

import static org.junit.Assert.assertEquals;

public class LdapAuthRuleSettingsTests {

  @Test
  public void testRuleSettingsSuccessfulCreation() {
    LdapAuthRuleSettings settings = LdapAuthRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_auth:\n" +
                                 "  name: ldap1\n" +
                                 "  groups: [group1, group2]")
        .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
    assertEquals("ldap_auth", settings.getName());
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotFoundLdapWithGivenName() {
    LdapAuthRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_auth:\n" +
                                 "  name: ldap3\n" +
                                 "  groups: [group1, group2]")
        .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToNotSetLdapAuthName() {
    LdapAuthRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_auth:\n" +
                                 "  groups: [group1, group2]")
        .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }

  @Test(expected = SettingsMalformedException.class)
  public void testRuleSettingsCreationFailsDueToEmptyGroupsSet() {
    LdapAuthRuleSettings.from(
      TestUtils.fromYAMLString("" +
                                 "ldap_auth:\n" +
                                 "  name: ldap1\n" +
                                 "  groups: []")
        .inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
      LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
    );
  }
}
