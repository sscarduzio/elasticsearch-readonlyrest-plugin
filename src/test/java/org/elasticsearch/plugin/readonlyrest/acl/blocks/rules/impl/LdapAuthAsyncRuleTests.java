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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Sets;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.testutils.esdependent.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.testutils.settings.MockLdapClientHelper;
import org.junit.Test;

import static org.elasticsearch.plugin.readonlyrest.testutils.mocks.RequestContextMock.mockedRequestContext;
import static org.junit.Assert.assertEquals;

public class LdapAuthAsyncRuleTests {

  @Test
  public void testUserShouldBeNotAuthenticatedIfLdapReturnError() throws Exception {
    LdapAuthAsyncRule rule = new LdapAuthAsyncRule(
        LdapAuthRuleSettings.from(
            RawSettings.fromString("" +
                "ldap_auth:\n" +
                "  name: ldap1\n" +
                "  groups: [group1, group2]").inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
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
    LdapAuthAsyncRule rule = new LdapAuthAsyncRule(
        LdapAuthRuleSettings.from(
            RawSettings.fromString("" +
                "ldap_auth:\n" +
                "  name: ldap2\n" +
                "  groups: [group1, group2]").inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
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
    LdapAuthAsyncRule rule = new LdapAuthAsyncRule(
        LdapAuthRuleSettings.from(
            RawSettings.fromString("" +
                "ldap_auth:\n" +
                "  name: ldap1\n" +
                "  groups: [group2, group3]").inner(LdapAuthRuleSettings.ATTRIBUTE_NAME),
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
