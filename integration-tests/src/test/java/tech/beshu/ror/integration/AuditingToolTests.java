package tech.beshu.ror.integration;

import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.integration.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.integration.ReadonlyRestedESAssertions;

import java.util.Optional;

import static tech.beshu.ror.utils.integration.ReadonlyRestedESAssertions.assertions;

public class AuditingToolTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container =
      ESWithReadonlyRestContainer.create(
          RorPluginGradleProject.fromSystemProperty(), "/auditing_tools/elasticsearch.yml",
          Optional.of(new ElasticsearchTweetsInitializer())
      );

  // todo: implement
//  @Test
//  public void checkCartmanCanSeeTwitter() throws Exception {
//    ReadonlyRestedESAssertions assertions = assertions(container);
//    assertions.assertUserHasAccessToIndex("user1", "dev", "twitter");
//  }
}
