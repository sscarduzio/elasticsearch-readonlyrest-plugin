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
package org.elasticsearch.plugin.readonlyrest.integration;

import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainerUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainer;
import org.elasticsearch.plugin.readonlyrest.utils.containers.MultiContainerDependent;
import org.elasticsearch.plugin.readonlyrest.utils.containers.WireMockContainer;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ElasticsearchTweetsInitializer;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ReadonlyRestedESAssertions;
import org.junit.Rule;
import org.junit.Test;

public class ExternalAuthenticationTests extends BaseIntegrationTests {

  @Rule
  public final MultiContainerDependent<ESWithReadonlyRestContainer> container;
  private final ReadonlyRestedESAssertions assertions;

  public ExternalAuthenticationTests(String esProject) {
    this.container = ESWithReadonlyRestContainerUtils.create(
        new RorPluginGradleProject(esProject),
        new MultiContainer.Builder()
            .add("EXT1", () -> WireMockContainer.create(
                "/external_authentication/wiremock_service1_cartman.json",
                "/external_authentication/wiremock_service1_morgan.json"
            ))
            .add("EXT2", () -> WireMockContainer.create("/external_authentication/wiremock_service2_cartman.json"))
            .build(),
        "/external_authentication/elasticsearch.yml",
        new ElasticsearchTweetsInitializer()
    );
    this.assertions = new ReadonlyRestedESAssertions(container);
  }

  @Test
  public void testAuthenticationSuccessWithService1() throws Exception {
    assertions.assertUserHasAccessToIndex("cartman", "user1", "twitter");
  }

  @Test
  public void testAuthenticationErrorWithService1() throws Exception {
    assertions.assertUserAccessToIndexForbidden("cartman", "user2", "twitter");
    assertions.assertUserAccessToIndexForbidden("morgan", "user2", "twitter");
  }

  @Test
  public void testAuthenticationSuccessWithService2() throws Exception {
    assertions.assertUserHasAccessToIndex("cartman", "user1", "facebook");
  }
}
