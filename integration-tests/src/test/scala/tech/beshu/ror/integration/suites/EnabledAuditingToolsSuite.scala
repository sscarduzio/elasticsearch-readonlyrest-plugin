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
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManagerJ, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait EnabledAuditingToolsSuite
  extends WordSpec
    with BasicSingleNodeEsClusterSupport
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/enabled_auditing_tools/readonlyrest.yml"

  override def nodeDataInitializer = Some(EnabledAuditingToolsSuite.nodeDataInitializer())

  private lazy val auditIndexManager = new AuditIndexManagerJ(adminClient, "audit_index")

  override def beforeEach() = {
    super.beforeEach()
    auditIndexManager.cleanAuditIndex
  }

  "Request" should {
    "be audited" when {
      "rule 1 is matching with logged user" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "ALLOWED"
        firstEntry.get("user") shouldBe "username"
        firstEntry.get("block").asInstanceOf[String].contains("name: 'Rule 1'") shouldBe true
      }

      "no rule is matching with username from auth header" in {
        val indexManager = new IndexManager(basicAuthClient("username", "wrong"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("user") shouldBe "username"
      }

      "no rule is matching with raw auth header as user" in {
        val indexManager = new IndexManager(basicAuthClient("usernameWithEmptyPass", ""))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("user") shouldBe "Basic dXNlcm5hbWVXaXRoRW1wdHlQYXNzOg=="
      }
    }
    "not be audited" when {
      "rule 2 is matching" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"))
        val response = indexManager.getIndex("facebook")
        response.responseCode shouldBe 200

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 0
      }
    }
  }
}

object EnabledAuditingToolsSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}