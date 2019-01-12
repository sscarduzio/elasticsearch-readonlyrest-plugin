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
package tech.beshu.ror.unit.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.unit.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapGroup;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapUser;
import tech.beshu.ror.mocks.MockLdapClientHelper;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;

import static org.junit.Assert.assertEquals;
import static tech.beshu.ror.mocks.RequestContextMock.mockedRequestContext;


public class LdapAuthenticationAsyncRuleTests {

  @Test
  public void testUserShouldBeNotAuthenticatedIfLdapReturnError() throws Exception {
    LdapAuthenticationAsyncRule rule = new LdapAuthenticationAsyncRule(
      LdapAuthenticationRuleSettings.from(
        TestUtils.fromYAMLString("" +
                                   "ldap_authentication:\n" +
                                   "  name: ldap1").inner(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
      ),
      MockLdapClientHelper.simpleFactory(MockLdapClientHelper.mockLdapClient()),
      MockedESContext.INSTANCE
    );
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

  @Test
  public void testUserShouldBeAuthenticatedIfLdapReturnSuccess() throws Exception {
    LdapAuthenticationAsyncRule rule = new LdapAuthenticationAsyncRule(
      LdapAuthenticationRuleSettings.from(
        TestUtils.fromYAMLString("" +
                                   "ldap_authentication:\n" +
                                   "  name: ldap2").inner(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME),
        LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
      ),
      MockLdapClientHelper.simpleFactory(MockLdapClientHelper.mockLdapClient(
        new LdapUser(
          "user",
          "cn=Example user,ou=People,dc=example,dc=com"
        ),
        Sets.newHashSet(new LdapGroup("group2"))
      )),
      MockedESContext.INSTANCE
    );
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(true, match.isMatch());
  }

}
