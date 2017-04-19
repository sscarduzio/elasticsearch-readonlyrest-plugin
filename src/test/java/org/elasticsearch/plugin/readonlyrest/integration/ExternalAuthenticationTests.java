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

public class ExternalAuthenticationTests {

  @ClassRule
  public static MultiContainerDependent<ESWithReadonlyRestContainer> container =
      ESWithReadonlyRestContainerUtils.create(
          new MultiContainer.Builder()
              .add("EXT1", () -> WireMockContainer.create(
                  "/external_authentication_test_wiremock_service1_cartman.json",
                  "/external_authentication_test_wiremock_service1_morgan.json"
              ))
              .add("EXT2", () -> WireMockContainer.create("/external_authentication_test_wiremock_service2_cartman.json"))
              .build(),
          "/external_authentication_test_elasticsearch.yml",
          new ElasticsearchTweetsInitializer()
      );

  private static ReadonlyRestedESAssertions assertions = new ReadonlyRestedESAssertions(container);

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
