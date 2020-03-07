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
package tech.beshu.ror.integration;

import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.*;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import static tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions.assertions;

public class ReverseProxyAuthenticationWithGroupsProviderAuthorizationTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
    ESWithReadonlyRestContainerUtils.create(
      RorPluginGradleProjectJ.fromSystemProperty(),
      new MultiContainer.Builder()
        .add("GROUPS1", () -> WireMockContainer.create("/rev_proxy_groups_provider/wiremock_service1_cartman.json",
          "/rev_proxy_groups_provider/wiremock_service1_morgan.json"
        ))
        .add("GROUPS2", () -> WireMockContainer.create("/rev_proxy_groups_provider/wiremock_service2.json"))
        .build(), "/rev_proxy_groups_provider/elasticsearch.yml",
      new ElasticsearchTweetsInitializer()
    );

  // #TODO doesnt pass
  @Test
  public void testAuthenticationAndAuthorizationSuccessWithService1() throws Exception {
    assertions(multiContainerDependent.getContainer()).assertReverseProxyUserHasAccessToIndex(
      "X-Auth-Token", "cartman", "twitter"
    );
  }

  @Test
  public void testAuthenticationAndAuthorizationErrorWithService1() throws Exception {
    assertions(multiContainerDependent.getContainer()).assertReverseProxyAccessToIndexForbidden(
      "X-Auth-Token", "morgan", "twitter"
    );
  }

  @Test
  public void testAuthenticationAndAuthorizationSuccessWithService2() throws Exception {
    assertions(multiContainerDependent.getContainer()).assertReverseProxyUserHasAccessToIndex(
      "X-Auth-Token", "29b3d166-1952-11e7-8b77-6c4008a76fc6", "facebook"
    );
  }
}
