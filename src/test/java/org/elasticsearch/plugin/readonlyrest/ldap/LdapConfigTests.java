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

package org.elasticsearch.plugin.readonlyrest.ldap;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.utils.containers.LdapContainer;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LdapConfigTests {

  @ClassRule
  public static LdapContainer ldapContainer = LdapContainer.create("/test_example.ldif");

  @Test
  public void testSuccessfulCreationFromRequiredSettings() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .put("bind_dn", "cn=admin,dc=example,dc=com")
                                .put("bind_password", "password")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }

  @Test(expected = ConfigMalformedException.class)
  public void testCreationFailedWhenNameWasNotPresentInSettings() {
    Settings settings = Settings.builder()
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }

  @Test(expected = ConfigMalformedException.class)
  public void testCreationFailedWhenHostWasNotPresentInSettings() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }

  @Test(expected = ConfigMalformedException.class)
  public void testCreationFailedWhenSearchUserBaseDNWasNotPresentInSettings() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }

  @Test
  public void testWhenSearchGroupsBaseDNWasNotPresentAuthenticationLdapClientIsBeingCreated() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .build();
    assertTrue(LdapConfig.fromSettings(settings, MockedESContext.INSTANCE).getClient() instanceof AuthenticationLdapClient);
  }

  @Test
  public void testWhenSearchGroupsBaseDNWasPresentGroupProviderLdapClientIsBeingCreated() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .build();
    assertTrue(LdapConfig.fromSettings(settings, MockedESContext.INSTANCE).getClient() instanceof GroupsProviderLdapClient);
  }

  @Test
  public void testBindDnAndPasswordAreNotRequiredParam() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }

  @Test(expected = ConfigMalformedException.class)
  public void testIfBindDnIsPresentBindPasswordMustBeProvided() {
    Settings settings = Settings.builder()
                                .put("name", "Ldap1")
                                .put("host", ldapContainer.getLdapHost())
                                .put("port", ldapContainer.getLdapPort())
                                .put("ssl_enabled", false)
                                .put("search_user_base_DN", "ou=People,dc=example,dc=com")
                                .put("search_groups_base_DN", "ou=Group,dc=example,dc=com")
                                .put("bind_dn", "cn=admin,dc=example,dc=com")
                                .build();
    LdapConfig.fromSettings(settings, MockedESContext.INSTANCE);
  }
}
