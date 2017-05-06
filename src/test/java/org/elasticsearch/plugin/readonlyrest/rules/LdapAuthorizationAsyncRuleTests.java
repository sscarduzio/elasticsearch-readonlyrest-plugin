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
package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.utils.settings.MockLdapClientHelper;
import org.junit.Test;

import static org.elasticsearch.plugin.readonlyrest.utils.mocks.RequestContextMock.mockedRequestContext;
import static org.junit.Assert.assertEquals;


public class LdapAuthorizationAsyncRuleTests {

  @Test
  public void testUserShouldBeAuthorizedIfLdapReturnSuccess() throws Exception {
    LdapAuthorizationAsyncRule rule = new LdapAuthorizationAsyncRule(
        LdapAuthorizationRuleSettings.from(
            RawSettings.fromString("" +
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
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(true, match.isMatch());
  }

  @Test
  public void testUserShouldNotBeAuthorizedIfLdapHasAGivenUserButWithinDifferentGroup() throws Exception {
    LdapAuthorizationAsyncRule rule = new LdapAuthorizationAsyncRule(
        LdapAuthorizationRuleSettings.from(
            RawSettings.fromString("" +
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
    RuleExitResult match = rule.match(mockedRequestContext("user", "pass")).get();
    assertEquals(false, match.isMatch());
  }

}
