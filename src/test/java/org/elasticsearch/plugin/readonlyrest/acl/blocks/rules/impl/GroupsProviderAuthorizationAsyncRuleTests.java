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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRuleFactory;
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.GroupsProviderAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.utils.containers.WireMockContainer;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

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
            RawSettings.fromString("" +
                "groups_provider_authorization:\n" +
                "  user_groups_provider: \"provider1\"\n" +
                "  groups: [" + Joiner.on(",").join(ruleGroups.stream().map(g -> "\"" + g + "\"").collect(Collectors.toSet())) + "]\n" +
                "  cache_ttl_in_sec: 60").inner(GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME),
            UserGroupsProviderSettingsCollection.from(
                RawSettings.fromString("" +
                    "user_groups_providers:\n" +
                    "  - name: provider1\n" +
                    "    groups_endpoint: \"http://localhost:" + wireMockContainer.getWireMockPort() + "/groups\"\n" +
                    "    auth_token_name: \"userId\"\n" +
                    "    auth_token_passed_as: QUERY_PARAM\n" +
                    "    response_groups_json_path: \"$..groups[?(@.name)].name\"\n"
                )
            )
        ),
        new DefinitionsFactory(new UserRuleFactory(MockedESContext.INSTANCE), MockedESContext.INSTANCE),
        MockedESContext.INSTANCE
    );

    LoggedUser user = new LoggedUser("example_user");
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    return rule.match(requestContext).get();
  }
}
