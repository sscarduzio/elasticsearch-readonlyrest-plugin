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

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.containers.WireMockContainer;
import org.elasticsearch.plugin.readonlyrest.utils.esdependent.MockedESContext;
import org.elasticsearch.plugin.readonlyrest.utils.settings.UserGroupsProviderConfigHelper;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.clients.GroupsProviderServiceHttpClient.TokenPassingMethod.QUERY;
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
    Settings settings = Settings.builder()
                                .put("groups_provider_authorization.user_groups_provider", "provider1")
                                .putArray("groups_provider_authorization.groups", ruleGroups)
                                .build();
    UserGroupProviderConfig config = UserGroupProviderConfig.fromSettings(
        UserGroupsProviderConfigHelper
            .create("provider1", new URI("http://localhost:" + wireMockContainer.getWireMockPort() + "/groups"),
                "userId", QUERY, "$..groups[?(@.name)].name")
            .getGroups("user_groups_providers")
            .get("0"));
    GroupsProviderAuthorizationAsyncRule rule = GroupsProviderAuthorizationAsyncRule.fromSettings(
        settings, Lists.newArrayList(config), MockedESContext.INSTANCE).get();

    LoggedUser user = new LoggedUser("example_user");
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    return rule.match(requestContext).get();
  }
}
