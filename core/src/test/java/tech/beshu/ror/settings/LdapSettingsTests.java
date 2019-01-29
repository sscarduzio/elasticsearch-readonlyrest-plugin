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

import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.AuthenticationLdapSettings;
import tech.beshu.ror.settings.definitions.__old_GroupsProviderLdapSettings;
import tech.beshu.ror.utils.containers.LdapContainer;

public class LdapSettingsTests {

  @ClassRule
  public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

  @Test
  public void testSuccessfulCreationFromRequiredSettings() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com\n" +
                                                              "bind_dn: cn=admin,dc=example,dc=com\n" +
                                                              "bind_password: password\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenNameWasNotPresentInSettings() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenSearchGroupBaseDnIsNotPresent() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "bind_dn: cn=admin,dc=example,dc=com\n" +
                                                              "bind_password: password\n"
    ));
  }

  @Test
  public void testCreationOfAuthenticationLdapEvenIfSearchGroupBaseDnIsNotPresent() {
    new AuthenticationLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "bind_dn: cn=admin,dc=example,dc=com\n" +
                                                              "bind_password: password\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenHostWasNotPresentInSettings() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenSearchUserBaseDNWasNotPresentInSettings() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com"
    ));
  }

  @Test
  public void testWhenSearchGroupsBaseDNWasPresentGroupProviderLdapClientIsBeingCreated() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com"
    ));
  }

  @Test
  public void testBindDnAndPasswordAreNotRequiredParam() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testIfBindDnIsPresentBindPasswordMustBeProvided() {
    new __old_GroupsProviderLdapSettings(TestUtils.fromYAMLString("" +
                                                              "name: ldap1\n" +
                                                              "host: " + ldapContainer.getLdapHost() + "\n" +
                                                              "port: " + ldapContainer.getLdapPort() + "\n" +
                                                              "ssl_enabled: false\n" +
                                                              "search_user_base_DN: ou=People,dc=example,dc=com\n" +
                                                              "search_groups_base_DN: ou=Group,dc=example,dc=com\n" +
                                                              "bind_dn: cn=admin,dc=example,dc=com"
    ));
  }
}
