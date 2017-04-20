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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ExternalAuthenticationServiceConfig;
import org.elasticsearch.plugin.readonlyrest.utils.settings.ExternalAuthenticationServiceConfigHelper;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ExternalAuthenticationServiceConfigTests {

  @Test
  public void testParsingCorrectConfiguration() throws URISyntaxException {
    String expectedName = "ext1";
    URI expectedGroupsEndpoint = new URI("http://localhost:12000/ext1");
    int expectedSuccessStatusCode = 200;
    Settings settings = ExternalAuthenticationServiceConfigHelper.create(expectedName, expectedGroupsEndpoint,
        expectedSuccessStatusCode);
    List<Settings> servicesSettings =
        Lists.newArrayList(settings.getGroups("external_authentication_service_configs").values());
    ExternalAuthenticationServiceConfig config = ExternalAuthenticationServiceConfig.fromSettings(servicesSettings.get(0));
    assertEquals(expectedName, config.getName());
    assertNotNull(config.getClient());
  }
}
