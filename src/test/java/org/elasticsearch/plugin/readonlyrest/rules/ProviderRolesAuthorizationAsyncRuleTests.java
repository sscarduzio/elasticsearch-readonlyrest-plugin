package org.elasticsearch.plugin.readonlyrest.rules;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProviderRolesAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig;
import org.elasticsearch.plugin.readonlyrest.utils.containers.WireMockContainer;
import org.elasticsearch.plugin.readonlyrest.utils.settings.UserRoleProviderConfigHelper;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig.TokenPassingMethod.QUERY;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class ProviderRolesAuthorizationAsyncRuleTests {

  @ClassRule
  public static WireMockContainer wireMockContainer = WireMockContainer.create("/provider_roles_authorization.json");

  @Test
  public void testUserAuthorizationSuccess() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("role1", "role3"));
    assertTrue(result.isMatch());
  }

  @Test
  public void testUserAuthorizationFailedDueToRoleMismatching() throws Exception {
    RuleExitResult result = createRuleRunMatch(Lists.newArrayList("role3", "role4"));
    assertFalse(result.isMatch());
  }

  private RuleExitResult createRuleRunMatch(List<String> ruleRoles) throws Exception {
    Settings settings = Settings.builder()
        .put("provider_roles_authorization.0.user_role_provider", "provider1")
        .putArray("provider_roles_authorization.0.roles", ruleRoles)
        .build();
    UserRoleProviderConfig config = UserRoleProviderConfig.fromSettings(
        UserRoleProviderConfigHelper
            .create("provider1", new URI("http://localhost:" + wireMockContainer.getWireMockPort() + "/roles"),
                "userId", QUERY, "$..roles[?(@.name)].name")
            .getGroups("user_role_providers")
            .get("0"));
    ProviderRolesAuthorizationAsyncRule rule = ProviderRolesAuthorizationAsyncRule.fromSettings(
        settings, Lists.newArrayList(config)).get();

    LoggedUser user = new LoggedUser("example_user");
    RequestContext requestContext = Mockito.mock(RequestContext.class);
    when(requestContext.getLoggedInUser()).thenReturn(Optional.of(user));

    return rule.match(requestContext).get();
  }
}
