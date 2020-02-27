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
import tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions;
import tech.beshu.ror.utils.containers.*;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import static tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions.assertions;

public class ExternalAuthenticationTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> multiContainerDependent =
    ESWithReadonlyRestContainerUtils.create(
      RorPluginGradleProjectJ.fromSystemProperty(),
      new MultiContainer.Builder()
        .add("EXT1", () -> WireMockContainer.create("/external_authentication/wiremock_service1_cartman.json",
          "/external_authentication/wiremock_service1_morgan.json"
        ))
        .add("EXT2", () -> WireMockContainer.create("/external_authentication/wiremock_service2_cartman.json"))
        .build(), "/external_authentication/elasticsearch.yml",
      new ElasticsearchTweetsInitializer()
    );

  @Test
  public void testAuthenticationSuccessWithService1() throws Exception {
    assertions(multiContainerDependent.getContainer()).assertUserHasAccessToIndex("cartman", "user1", "twitter");
  }

  @Test
  public void testAuthenticationErrorWithService1() throws Exception {
    ReadonlyRestedESAssertions assertions = assertions(multiContainerDependent.getContainer());
    assertions.assertUserAccessToIndexForbidden("cartman", "user2", "twitter");
    assertions.assertUserAccessToIndexForbidden("morgan", "user2", "twitter");
  }

  @Test
  public void testAuthenticationSuccessWithService2() throws Exception {
    assertions(multiContainerDependent.getContainer()).assertUserHasAccessToIndex("cartman", "user1", "facebook");
  }
}
