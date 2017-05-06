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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders.GroupsProviderServiceHttpClient;
import org.elasticsearch.plugin.readonlyrest.utils.settings.UserGroupsProviderConfigHelper;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class UserGroupsProviderConfigTests {

  @Test
  public void testParsingCorrectConfiguration() throws URISyntaxException {
    String expectedName = "groupprovider1";
    String expectedAuthTokenName = "token";
    URI expectedGroupsEndpoint = new URI("http://localhost:12000/groups_list");
    String expectedResponseGroupsJsonPath = "$..groups[?(@.name)].name";
    Settings settings = UserGroupsProviderConfigHelper.create(expectedName, expectedGroupsEndpoint, expectedAuthTokenName,
        GroupsProviderServiceHttpClient.TokenPassingMethod.QUERY, expectedResponseGroupsJsonPath);
    List<Settings> userGroupsProvidersSettings = Lists.newArrayList(settings.getGroups("user_groups_providers").values());
    UserGroupProviderConfig config = UserGroupProviderConfig.fromSettings(userGroupsProvidersSettings.get(0));
    assertEquals(expectedName, config.getName());
    assertNotNull(config.getClient());
  }

}
