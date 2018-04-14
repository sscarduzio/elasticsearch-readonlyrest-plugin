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

package tech.beshu.ror.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import org.junit.Test;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.definitions.ldaps.LdapGroup;
import tech.beshu.ror.acl.definitions.ldaps.LdapUser;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.mocks.MockLdapClientHelper;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.mocks.RequestContextMock;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;

import java.util.Set;

import static org.junit.Assert.assertEquals;

public class LdapAuthorizationAsyncRuleTests {

  @Test
  public void testUserShouldBeAuthorizedIfLdapReturnSuccess() throws Exception {
    LdapAuthorizationAsyncRule rule = new LdapAuthorizationAsyncRule(
        LdapAuthorizationRuleSettings.from(
            TestUtils.fromYAMLString("" +
                "ldap_authorization:\n" +
                "  name: ldap2\n" +
                "  groups: [group1, group2]").inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
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
    RequestContext rc = RequestContextMock.mockedRequestContext("user", "pass", "group2");
    RuleExitResult match = rule.match(rc).get();

    // Available groups
    assertEquals(true, match.isMatch());
    LoggedUser lu = rc.getLoggedInUser().get();
    Set<String> availGroups = lu.getAvailableGroups();
    assertEquals(1, availGroups.size());
    assertEquals("group2", availGroups.iterator().next());
    assertEquals("group2", lu.resolveCurrentGroup(rc.getHeaders()));
  }

  @Test
  public void testUserShouldNotBeAuthorizedIfLdapHasAGivenUserButWithinDifferentGroup() throws Exception {
    LdapAuthorizationAsyncRule rule = new LdapAuthorizationAsyncRule(
        LdapAuthorizationRuleSettings.from(
            TestUtils.fromYAMLString("" +
                "ldap_authorization:\n" +
                "  name: ldap1\n" +
                "  groups: [group2, group3]").inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
            LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
        ),
        MockLdapClientHelper.simpleFactory(MockLdapClientHelper.mockLdapClient(
            new LdapUser(
                "user",
                "cn=Example user,ou=People,dc=example,dc=com"
            ),
            Sets.newHashSet(new LdapGroup("group5"))
        )),
        MockedESContext.INSTANCE
    );
    RuleExitResult match = rule.match(RequestContextMock.mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

  @Test
  public void testUserShouldNotBeAuthorizedIfLdapHasAGivenUserButLdapGroupsAreEmpty() throws Exception {
    LdapAuthorizationAsyncRule rule = new LdapAuthorizationAsyncRule(
        LdapAuthorizationRuleSettings.from(
            TestUtils.fromYAMLString("" +
                "ldap_authorization:\n" +
                "  name: ldap1\n" +
                "  groups: [group2, group3]").inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
            LdapSettingsCollection.from(MockLdapClientHelper.mockLdapsCollection())
        ),
        MockLdapClientHelper.simpleFactory(MockLdapClientHelper.mockLdapClient(
            new LdapUser(
                "user",
                "cn=Example user,ou=People,dc=example,dc=com"
            ),
            Sets.newHashSet()
        )),
        MockedESContext.INSTANCE
    );
    RuleExitResult match = rule.match(RequestContextMock.mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

}
