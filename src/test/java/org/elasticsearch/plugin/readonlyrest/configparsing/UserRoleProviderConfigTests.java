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
