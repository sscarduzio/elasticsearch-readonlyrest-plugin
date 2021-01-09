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

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManagerJ, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait DisabledAuditingToolsSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/disabled_auditing_tools/readonlyrest.yml"

  override def nodeDataInitializer = Some(DisabledAuditingToolsSuite.nodeDataInitializer())

  private lazy val auditIndexManager = new AuditIndexManagerJ(basicAuthClient("admin", "container"), "audit_index")

  override def beforeEach() = {
    super.beforeEach()
    auditIndexManager.cleanAuditIndex
  }

  "Request" should {
    "not be audited" when {
      "rule 1 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        val auditResponse = auditIndexManager.auditIndexSearch()
        auditResponse.isSuccess shouldBe false
      }
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "dev"))
        val response = indexManager.getIndex("facebook")
        response.responseCode shouldBe 200

        val auditResponse = auditIndexManager.auditIndexSearch()
        auditResponse.isSuccess shouldBe false
      }
      "no rule is matching" in {
        val indexManager = new IndexManager(basicAuthClient("user", "wrong"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditResponse = auditIndexManager.auditIndexSearch()
        auditResponse.isSuccess shouldBe false
      }
    }
  }
}

object DisabledAuditingToolsSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
