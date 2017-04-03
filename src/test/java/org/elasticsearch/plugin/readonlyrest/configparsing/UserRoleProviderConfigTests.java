package org.elasticsearch.plugin.readonlyrest.configparsing;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class UserRoleProviderConfigTests {

  @Test
  public void testParsingCorrectConfiguration() throws URISyntaxException {
    String expectedName = "roleprovider1";
    String expectedAuthTokenName = "token";
    URI expectedRoleEndpoint = new URI("http://localhost:12000/roles_list");
    String expectedResponseRolesJsonPath = "$..roles[?(@.name)]";
    Settings settings = Settings.builder()
        .put("user_role_providers.0.name", expectedName)
        .put("user_role_providers.0.role_endpoint", expectedRoleEndpoint.toString())
        .put("user_role_providers.0.auth_token_name", expectedAuthTokenName)
        .put("user_role_providers.0.auth_token_passed_as", "QUERY_PARAM")
        .put("user_role_providers.0.response_roles_json_path", expectedResponseRolesJsonPath)
        .build();
    List<Settings> userRoleProvidersSettings = Lists.newArrayList(settings.getGroups("user_role_providers").values());
    UserRoleProviderConfig config = UserRoleProviderConfig.fromSettings(userRoleProvidersSettings.get(0));
    assertEquals(expectedName, config.getName());
    assertEquals(expectedRoleEndpoint, config.getEndpoint());
    assertEquals(expectedAuthTokenName, config.getAuthTokenName());
    assertEquals(UserRoleProviderConfig.TokenPassingMethod.QUERY, config.getPassingMethod());
    assertEquals(expectedResponseRolesJsonPath, config.getResponseRolesJsonPath());
  }

}
