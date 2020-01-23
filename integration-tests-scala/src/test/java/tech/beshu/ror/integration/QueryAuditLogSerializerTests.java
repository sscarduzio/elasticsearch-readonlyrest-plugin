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

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.elasticsearch.AuditIndexManagerJ;
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

  private final AuditIndexManagerJ auditIndexManager = new AuditIndexManagerJ(
      container.getBasicAuthClient("admin", "container"),
      "audit_index"
  );

  @Before
  public void beforeEach() {
    auditIndexManager.cleanAuditIndex();
  }

  @Test
  public void rule1MatchingRequestShouldBeAudited() throws Exception {
    assertions(container).assertUserHasAccessToIndex("user", "dev", "twitter");
    List<Map<String, Object>> auditEntries = auditIndexManager.auditIndexSearch().getEntries();

    assertEquals(1, auditEntries.size());
    assertEquals("ALLOWED", auditEntries.get(0).get("final_state"));
    assertEquals("", auditEntries.get(0).get("content"));
    assertThat((String) auditEntries.get(0).get("block"), containsString("name: 'Rule 1'"));
  }

  @Test
  public void noRuleMatchingRequestShouldBeAudited() throws Exception {
    assertions(container).assertUserAccessToIndexForbidden("user", "wrong", "twitter");
    List<Map<String, Object>> auditEntries = auditIndexManager.auditIndexSearch().getEntries();

    assertEquals(1, auditEntries.size());
    assertEquals("FORBIDDEN", auditEntries.get(0).get("final_state"));
    assertEquals("", auditEntries.get(0).get("content"));
  }

  @Test
  public void rule2MatchingRequestShouldNotBeAudited() throws Exception {
    assertions(container).assertUserHasAccessToIndex("user", "dev", "facebook");
    List<Map<String, Object>> auditEntries = auditIndexManager.auditIndexSearch().getEntries();

    assertEquals(0, auditEntries.size());
  }
}
