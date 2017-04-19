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
    assertEquals(expectedGroupsEndpoint, config.getEndpoint());
    assertEquals(expectedSuccessStatusCode, config.getSuccessStatusCode());
  }
}
