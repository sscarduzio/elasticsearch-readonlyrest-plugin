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
package tech.beshu.ror.integration.suites

import org.junit.Assert.assertEquals
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager, RorApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import ujson.Str

trait QueryAuditLogSerializerSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers
    with CustomScalaTestMatchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/query_audit_log_serializer/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = QueryAuditLogSerializerSuite.nodeDataInitializer(),
      xPackSupport = false,
    )
  )

  private lazy val auditIndexManager = new AuditIndexManager(adminClient, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    auditIndexManager.truncate
  }

  "Request" should {
    "be audited" when {
      "user metadata context for failed login" in {
        val user1MetadataManager = new RorApiManager(basicAuthClient("user2", "dev"))

        val result = user1MetadataManager.fetchMetadata()

        assertEquals(403, result.responseCode)
        result.responseJson.obj.size should be(1)

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size shouldBe 1
        val firstEntry = auditEntries(0)
        firstEntry("user").str should be("user2")
        firstEntry("final_state").str shouldBe "FORBIDDEN"
        firstEntry("block").str should include("""default""")
        firstEntry("content").str shouldBe ""
      }
      "user metadata context" in {
        val user1MetadataManager = new RorApiManager(authHeader("X-Auth-Token", "user1-proxy-id"))

        val result = user1MetadataManager.fetchMetadata()

        assertEquals(200, result.responseCode)
        result.responseJson.obj.size should be(4)
        result.responseJson("x-ror-username").str should be("user1-proxy-id")
        result.responseJson("x-ror-current-group").str should be("group1")
        result.responseJson("x-ror-available-groups").arr.toList should be(List(Str("group1")))
        result.responseJson("x-ror-logging-id").str should fullyMatch uuidRegex()

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditIndexManager.getEntries.jsons(0)
        firstEntry("user").str should be("user1-proxy-id")
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("block").str should include("""name: 'Allowed only for group1'""")
        firstEntry("content").str shouldBe ""
      }
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("user").str should be ("user")
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("block").str should include ("name: 'Rule 1'")
        firstEntry("content").str shouldBe ""
      }
      "no rule is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "wrong"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "FORBIDDEN"
        firstEntry("content").str shouldBe ""
      }
    }
    "not be audited" when {
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("facebook")
        response.responseCode shouldBe 200

        val auditEntriesResponse = auditIndexManager.getEntries
        auditEntriesResponse.responseCode should be (404)
      }
    }
  }
}

object QueryAuditLogSerializerSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
