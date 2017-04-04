package org.elasticsearch.plugin.readonlyrest.integration;

import org.elasticsearch.plugin.readonlyrest.utils.containers.*;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ElasticsearchTweetsInitializer;
import org.elasticsearch.plugin.readonlyrest.utils.integration.ReadonlyRestedESAssertions;
import org.junit.ClassRule;
import org.junit.Test;

public class ReverseProxyAuthenticationWithRoleBaseAuthorizationTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainerUtils.create(
      new MultiContainer.Builder()
          .add("ROLES", () -> WireMockContainer.create("/role_based_authorization_test_wiremock.json"))
          .build(),
      "/role_based_authorization_test_elasticsearch.yml",
      new ElasticsearchTweetsInitializer()
  );

  private static ReadonlyRestedESAssertions assertions = new ReadonlyRestedESAssertions(container);

  @Test
  public void test() throws Exception {
    assertions.assertReverseProxyUserHasAccessToIndex("X-Auth-Token", "cartman", "twitter");
  }
}
