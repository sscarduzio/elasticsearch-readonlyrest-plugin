package tech.beshu.ror.integration;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.elasticsearch.AuditIndexManager;
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static tech.beshu.ror.utils.assertions.ReadonlyRestedESAssertions.assertions;

public class QueryAuditLogSerializerTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = ESWithReadonlyRestContainer.create(
      RorPluginGradleProjectJ.fromSystemProperty(), "/query_audit_log_serializer/elasticsearch.yml",
      Optional.of(new ElasticsearchTweetsInitializer())
  );

  private final AuditIndexManager auditIndexManager = new AuditIndexManager(
      container.getBasicAuthClient("admin", "container"),
      "audit_index"
  );

  @Before
  public void beforeEach() throws Exception {
    auditIndexManager.cleanAuditIndex();
  }

  @Test
  public void rule1MatchingRequestShouldBeAudited() throws Exception {
    assertions(container).assertUserHasAccessToIndex("user", "dev", "twitter");
    List<Map<String, Object>> auditEntries = auditIndexManager.getAuditIndexEntries();

    assertEquals(1, auditEntries.size());
    assertEquals("ALLOWED", auditEntries.get(0).get("final_state"));
    assertEquals("", auditEntries.get(0).get("content"));
    assertThat((String) auditEntries.get(0).get("block"), containsString("name: 'Rule 1'"));
  }

  @Test
  public void noRuleMatchingRequestShouldBeAudited() throws Exception {
    assertions(container).assertUserAccessToIndexForbidden("user", "wrong", "twitter");
    List<Map<String, Object>> auditEntries = auditIndexManager.getAuditIndexEntries();

    assertEquals(1, auditEntries.size());
    assertEquals("FORBIDDEN", auditEntries.get(0).get("final_state"));
    assertEquals("", auditEntries.get(0).get("content"));
  }

  @Test
  public void rule2MatchingRequestShouldNotBeAudited() throws Exception {
    assertions(container).assertUserHasAccessToIndex("user", "dev", "facebook");
    List<Map<String, Object>> auditEntries = auditIndexManager.getAuditIndexEntries();

    assertEquals(0, auditEntries.size());
  }
}
