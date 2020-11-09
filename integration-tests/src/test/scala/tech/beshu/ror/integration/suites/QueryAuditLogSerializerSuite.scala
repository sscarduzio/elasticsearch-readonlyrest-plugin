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

import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait QueryAuditLogSerializerSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers {
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

  private lazy val auditIndexManager = new AuditIndexManager(adminClient, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    auditIndexManager.truncate
  }

  "Request" should {
    "be audited" when {
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.getEntries.jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("block").str should contain ("name: 'Rule 1'")
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

        val auditEntries = auditIndexManager.getEntries.jsons
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
