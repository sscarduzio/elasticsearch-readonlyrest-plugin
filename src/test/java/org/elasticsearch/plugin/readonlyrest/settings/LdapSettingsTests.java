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

import org.elasticsearch.plugin.readonlyrest.settings.definitions.AuthenticationLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;
import org.elasticsearch.plugin.readonlyrest.utils.containers.LdapContainer;
import org.junit.ClassRule;
import org.junit.Test;

public class LdapSettingsTests {

  @ClassRule
  public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

  @Test
  public void testSuccessfulCreationFromRequiredSettings() {
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
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
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
        "host: " + ldapContainer.getLdapHost() + "\n" +
        "port: " + ldapContainer.getLdapPort() + "\n" +
        "ssl_enabled: false\n" +
        "search_user_base_DN: ou=People,dc=example,dc=com\n" +
        "search_groups_base_DN: ou=Group,dc=example,dc=com\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenSearchGroupBaseDnIsNotPresent() {
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
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
    new AuthenticationLdapSettings(RawSettings.fromString("" +
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
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
        "name: ldap1\n" +
        "search_user_base_DN: ou=People,dc=example,dc=com\n" +
        "search_groups_base_DN: ou=Group,dc=example,dc=com\n"
    ));
  }

  @Test(expected = SettingsMalformedException.class)
  public void testCreationFailedWhenSearchUserBaseDNWasNotPresentInSettings() {
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
        "name: ldap1\n" +
        "host: " + ldapContainer.getLdapHost() + "\n" +
        "port: " + ldapContainer.getLdapPort() + "\n" +
        "ssl_enabled: false\n" +
        "search_groups_base_DN: ou=Group,dc=example,dc=com"
    ));
  }

  @Test
  public void testWhenSearchGroupsBaseDNWasPresentGroupProviderLdapClientIsBeingCreated() {
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
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
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
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
    new GroupsProviderLdapSettings(RawSettings.fromString("" +
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
