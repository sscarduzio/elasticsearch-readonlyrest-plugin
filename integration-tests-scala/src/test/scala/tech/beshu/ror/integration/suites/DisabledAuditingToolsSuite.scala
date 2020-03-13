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
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManagerJ, ElasticsearchTweetsInitializer, IndexManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait DisabledAuditingToolsSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/disabled_auditing_tools/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = DisabledAuditingToolsSuite.nodeDataInitializer()
    )
  )

  private lazy val auditIndexManager = new AuditIndexManagerJ(client("admin", "container"), "audit_index")

  override def beforeEach() = {
    super.beforeEach()
    auditIndexManager.cleanAuditIndex
  }

  "Request" should {
    "not be audited" when {
      "rule 1 is matching" in {
        val indexManager = new IndexManagerJ(client("user", "dev"))
        val response = indexManager.get("twitter")
        assertEquals(200, response.getResponseCode)

        val auditResponse = auditIndexManager.auditIndexSearch()
        assertEquals(false, auditResponse.isSuccess)
      }
      "rule 2 is matching" in {
        val indexManager = new IndexManagerJ(client("user", "dev"))
        val response = indexManager.get("facebook")
        assertEquals(200, response.getResponseCode)

        val auditResponse = auditIndexManager.auditIndexSearch()
        assertEquals(false, auditResponse.isSuccess)
      }
      "no rule is matching" in {
        val indexManager = new IndexManagerJ(client("user", "wrong"))
        val response = indexManager.get("twitter")
        assertEquals(403, response.getResponseCode)

        val auditResponse = auditIndexManager.auditIndexSearch()
        assertEquals(false, auditResponse.isSuccess)
      }
    }
  }
}

object DisabledAuditingToolsSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
