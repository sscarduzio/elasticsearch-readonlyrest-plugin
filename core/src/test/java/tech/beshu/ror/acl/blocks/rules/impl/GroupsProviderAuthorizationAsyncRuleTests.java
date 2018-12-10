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

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.jetbrains.annotations.NotNull;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;
import tech.beshu.ror.TestUtils;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.domain.LoggedUser;
import tech.beshu.ror.mocks.MockedACL;
import tech.beshu.ror.mocks.MockedESContext;
import tech.beshu.ror.requestcontext.__old_RequestContext;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;
import tech.beshu.ror.utils.containers.WireMockContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class GroupsProviderAuthorizationAsyncRuleTests {

  @ClassRule
  public static WireMockContainer wireMockContainer = WireMockContainer.create(
      "/groups_provider_authorization.json",
      "/group_provider_authorization_complex.json");

  private final Function<ImmutableMap<String, String>, String> mapFormatter = map -> map.entrySet().stream().
      map(y -> "      " + y.getKey() + ": " + y.getValue()).reduce("", (x, y) -> x + "\n" + y).concat("\n");

  @Test
  public void testUserAuthorizationSuccess() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("group1", "group3"),
        "/groups", null, null, null, (user) -> {
          assertEquals("group1", user.getCurrentGroup().get());
          assertEquals("group1", Joiner.on(",").join(user.getAvailableGroups()));
          return null;
        }, null);
    assertTrue(result.isMatch());
    result = createRuleRunMatch(Lists.newArrayList("group1", "group2"),
        "/complex/groups", "POST",
        ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded",
            "Auth", "Basic base64_encoded_token"),
        ImmutableMap.of("return_groups", "true"), (user) -> {
          assertEquals("group1", user.getCurrentGroup().get());
          assertTrue(user.getAvailableGroups().contains("group1"));
          assertTrue(user.getAvailableGroups().contains("group2"));
          return null;
        }, "group1"
    );
    assertTrue(result.isMatch());
  }

  @Test
  public void testUserAuthorizationFailedDueToGroupMismatching() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("group3", "group4"),
        "/groups", null, null, null, null, null);
    assertFalse(result.isMatch());
    result = createRuleRunMatch(Lists.newArrayList("group1", "group3"),
        "/complex/groups", "POST",
        ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded",
            "Authorization", "Basic base64_encoded_token"),
        ImmutableMap.of("return_Role", "true"), null, null
    );
    assertFalse(result.isMatch());

    result = createRuleRunMatch(Lists.newArrayList("group1", "group3"),
        "/complex/groups", "POST",
        ImmutableMap.of("Content-Type", "application/x-www-form-urlencoded"),
        ImmutableMap.of("return_Role", "true"), null, null
    );
    assertFalse(result.isMatch());
  }

  private RuleExitResult createRuleRunMatch(
      List<String> ruleGroups, String uri, String method, ImmutableMap<String, String> headers,
      ImmutableMap<String, String> query_params, Function<LoggedUser, Void> userAssertions, String currentGroup) throws Exception {

    GroupsProviderAuthorizationAsyncRule rule = new GroupsProviderAuthorizationAsyncRule(
        GroupsProviderAuthorizationRuleSettings.from(
            TestUtils.fromYAMLString(groupProviderConfig(ruleGroups)).inner(GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME),
            UserGroupsProviderSettingsCollection.from(
                TestUtils.fromYAMLString(apiConfigs(uri, method, headers, query_params)
                )
            )
        ),
        new DefinitionsFactory(MockedESContext.INSTANCE, MockedACL.getMock()),
        MockedESContext.INSTANCE
    );

    LoggedUser user = new LoggedUser("example_user");
    __old_RequestContext requestContext = Mockito.mock(__old_RequestContext.class);
    if (currentGroup != null) {
      Map<String, String> hmap = new HashMap<>(1);
      hmap.put(Constants.HEADER_GROUP_CURRENT, "group1");
      when(requestContext.getHeaders()).thenReturn(hmap);
    }
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    RuleExitResult res = rule.match(requestContext).get();
    if (userAssertions != null) {
      userAssertions.apply(user);
    }
    return res;
  }

  @NotNull
  private String apiConfigs(String uri, String method, ImmutableMap<String, String> headers, ImmutableMap<String, String> query_params) {
    StringBuilder builder = new StringBuilder("" +
        "user_groups_providers:\n" +
        "  - name: provider1\n" +
        "    groups_endpoint: \"http://localhost:" + wireMockContainer.getWireMockPort() + uri + "\"\n" +
        "    auth_token_name: \"userId\"\n" +
        "    auth_token_passed_as: QUERY_PARAM\n" +
        "    response_groups_json_path: \"$..groups[?(@.name)].name\"\n");
    builder
        .append(headers != null ? new StringBuilder().
                                                         append("    default_headers:\n").append(mapFormatter.apply(headers)).toString() : "");
    builder
        .append(headers != null ? new StringBuilder().
                                                         append("    default_query_parameters:\n").append(mapFormatter.apply(query_params)).toString() : "");
    builder.append(method != null ? "    http_method: " + method : "");
    return builder.toString();
  }

  @NotNull
  private String groupProviderConfig(List<String> ruleGroups) {
    return "" +
        "groups_provider_authorization:\n" +
        "  user_groups_provider: \"provider1\"\n" +
        "  groups: [" + Joiner.on(",").join(ruleGroups.stream().map(g -> "\"" + g + "\"").collect(Collectors.toSet())) + "]\n" +
        "  cache_ttl_in_sec: 60";
  }

}
