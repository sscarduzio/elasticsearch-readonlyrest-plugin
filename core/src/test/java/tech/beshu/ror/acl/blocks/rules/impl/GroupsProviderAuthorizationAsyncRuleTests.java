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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.mocks.MockedACL;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;
import tech.beshu.ror.utils.containers.WireMockContainer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class GroupsProviderAuthorizationAsyncRuleTests {

  @ClassRule
  public static WireMockContainer wireMockContainer = WireMockContainer.create("/groups_provider_authorization.json");

  @Test
  public void testUserAuthorizationSuccess() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("group1", "group3"));
    assertTrue(result.isMatch());
  }

  @Test
  public void testUserAuthorizationFailedDueToGroupMismatching() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("group3", "group4"));
    assertFalse(result.isMatch());
  }

  private RuleExitResult createRuleRunMatch(List<String> ruleGroups) throws Exception {
    GroupsProviderAuthorizationAsyncRule rule = new GroupsProviderAuthorizationAsyncRule(
      GroupsProviderAuthorizationRuleSettings.from(
        TestUtils.fromYAMLString("" +
                                   "groups_provider_authorization:\n" +
                                   "  user_groups_provider: \"provider1\"\n" +
                                   "  groups: [" + Joiner.on(",").join(ruleGroups.stream().map(g -> "\"" + g + "\"").collect(Collectors.toSet())) + "]\n" +
                                   "  cache_ttl_in_sec: 60").inner(GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME),
        UserGroupsProviderSettingsCollection.from(
          TestUtils.fromYAMLString("" +
                                     "user_groups_providers:\n" +
                                     "  - name: provider1\n" +
                                     "    groups_endpoint: \"http://localhost:" + wireMockContainer.getWireMockPort() + "/groups\"\n" +
                                     "    auth_token_name: \"userId\"\n" +
                                     "    auth_token_passed_as: QUERY_PARAM\n" +
                                     "    response_groups_json_path: \"$..groups[?(@.name)].name\"\n"
          )
        )
      ),
      new DefinitionsFactory(MockedESContext.INSTANCE, MockedACL.getMock()),
      MockedESContext.INSTANCE
    );

    LoggedUser user = new LoggedUser("example_user");
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    return rule.match(requestContext).get();
  }
}
