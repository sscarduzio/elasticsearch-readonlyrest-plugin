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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator, NoXpackSupport}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManagerJ, ElasticsearchTweetsInitializer, IndexManager, RorApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.Str

trait QueryAuditLogSerializerSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers
    with NoXpackSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/query_audit_log_serializer/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = QueryAuditLogSerializerSuite.nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  private lazy val auditIndexManager = new AuditIndexManagerJ(basicAuthClient("admin", "container"), "audit_index")

  override def beforeEach() = {
    super.beforeEach()
    auditIndexManager.cleanAuditIndex
  }

  "Request" should {
    "be audited" when {
      "user metadata context for failed login" in {
        val user1MetadataManager = new RorApiManager(basicAuthClient("user2", "dev"))

        val result = user1MetadataManager.fetchMetadata()

        assertEquals(403, result.responseCode)
        result.responseJson.obj.size should be(1)
        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("user") should be("user2")
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("block").asInstanceOf[String] should include("""default""")
        firstEntry.get("content") shouldBe ""
      }
      "user metadata context" in {
        val user1MetadataManager = new RorApiManager(authHeader("X-Auth-Token", "user1-proxy-id"))

        val result = user1MetadataManager.fetchMetadata()

        assertEquals(200, result.responseCode)
        result.responseJson.obj.size should be(3)
        result.responseJson("x-ror-username").str should be("user1-proxy-id")
        result.responseJson("x-ror-current-group").str should be("group1")
        result.responseJson("x-ror-available-groups").arr.toList should be(List(Str("group1")))
        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("user") should be("user1-proxy-id")
        firstEntry.get("final_state") shouldBe "ALLOWED"
        firstEntry.get("block").asInstanceOf[String] should include("""name: 'Allowed only for group1""")
        firstEntry.get("content") shouldBe ""
      }
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("user") should be ("user")
        firstEntry.get("final_state") shouldBe "ALLOWED"
        firstEntry.get("block").asInstanceOf[String].contains("name: 'Rule 1'") shouldBe true
        firstEntry.get("content") shouldBe ""
      }

      "no rule is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "wrong"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("content") shouldBe ""
      }
    }
    "not be audited" when {
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("facebook")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 0
      }
    }
  }
}

object QueryAuditLogSerializerSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
