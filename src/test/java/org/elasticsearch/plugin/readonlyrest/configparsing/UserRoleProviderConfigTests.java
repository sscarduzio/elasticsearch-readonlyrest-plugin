package org.elasticsearch.plugin.readonlyrest.configparsing;

import com.google.common.collect.Lists;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig;
import org.elasticsearch.plugin.readonlyrest.utils.settings.UserRoleProviderConfigHelper;
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
    String expectedResponseRolesJsonPath = "$..roles[?(@.name)].name";
    Settings settings = UserRoleProviderConfigHelper.create(expectedName, expectedRoleEndpoint, expectedAuthTokenName,
        UserRoleProviderConfig.TokenPassingMethod.QUERY, expectedResponseRolesJsonPath);
    List<Settings> userRoleProvidersSettings = Lists.newArrayList(settings.getGroups("user_role_providers").values());
    UserRoleProviderConfig config = UserRoleProviderConfig.fromSettings(userRoleProvidersSettings.get(0));
    assertEquals(expectedName, config.getName());
    assertEquals(expectedRoleEndpoint, config.getEndpoint());
    assertEquals(expectedAuthTokenName, config.getAuthTokenName());
    assertEquals(UserRoleProviderConfig.TokenPassingMethod.QUERY, config.getPassingMethod());
    assertEquals(expectedResponseRolesJsonPath, config.getResponseRolesJsonPath());
  }

}
