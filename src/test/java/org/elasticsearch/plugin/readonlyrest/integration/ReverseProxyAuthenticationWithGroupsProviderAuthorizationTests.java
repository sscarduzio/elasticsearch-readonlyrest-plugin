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
import org.elasticsearch.plugin.readonlyrest.utils.integration.ElasticsearchTweetsInitializer;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ReadonlyRestedESAssertions;
import org.junit.ClassRule;
import org.junit.Test;

public class ReverseProxyAuthenticationWithGroupsProviderAuthorizationTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> container =
      ESWithReadonlyRestContainerUtils.create(
          new MultiContainer.Builder()
              .add("GROUPS1", () -> WireMockContainer.create(
                  "/groups_provider_authorization_test_wiremock_service1_cartman.json",
                  "/groups_provider_authorization_test_wiremock_service1_morgan.json"
              ))
              .add("GROUPS2", () -> WireMockContainer.create("/groups_provider_authorization_test_wiremock_service2.json"))
              .build(),
          "/groups_provider_authorization_test_elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  private static ReadonlyRestedESAssertions assertions = new ReadonlyRestedESAssertions(container);

  @Test
  public void testAuthenticationAndAuthorizationSuccessWithService1() throws Exception {
    assertions.assertReverseProxyUserHasAccessToIndex("X-Auth-Token", "cartman", "twitter");
  }

  @Test
  public void testAuthenticationAndAuthorizationErrorWithService1() throws Exception {
    assertions.assertReverseProxyAccessToIndexForbidden("X-Auth-Token", "morgan", "twitter");
  }

  @Test
  public void testAuthenticationAndAuthorizationSuccessWithService2() throws Exception {
    assertions.assertReverseProxyUserHasAccessToIndex("X-Auth-Token", "29b3d166-1952-11e7-8b77-6c4008a76fc6", "facebook");
  }
}
