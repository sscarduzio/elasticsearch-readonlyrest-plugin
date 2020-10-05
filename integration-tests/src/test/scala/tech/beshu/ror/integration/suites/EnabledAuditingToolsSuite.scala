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

import java.util.UUID

import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManagerJ, ElasticsearchTweetsInitializer, IndexManager, RorApiManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait EnabledAuditingToolsSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/enabled_auditing_tools/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = EnabledAuditingToolsSuite.nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  private lazy val auditIndexManager = new AuditIndexManagerJ(adminClient, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    auditIndexManager.cleanAuditIndex
  }

  "Request" should {
    "be audited" when {
      "rule 1 is matched with logged user" in {
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
      "no rule is matched with username from auth header" in {
        val indexManager = new IndexManager(
          basicAuthClient("username", "wrong")
        )
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("user") shouldBe "username"
      }
      "no rule is matched with raw auth header as user" in {
        val indexManager = new IndexManager(tokenAuthClient("user_token"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        val auditEntries = auditIndexManager.auditIndexSearch().getEntries
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries.get(0)
        firstEntry.get("final_state") shouldBe "FORBIDDEN"
        firstEntry.get("user") shouldBe "user_token"
      }
      "rule 1 is matched" when {
        "two requests were sent and correlationId is the same for both of them" in {
          val correlationId = UUID.randomUUID().toString
          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val response1 = indexManager.getIndex("twitter")
          response1.responseCode shouldBe 200

          val response2 = indexManager.getIndex("twitter")
          response2.responseCode shouldBe 200

          val auditEntries = auditIndexManager.auditIndexSearch().getEntries
          auditEntries.size shouldBe 2

          auditEntries.get(0).get("correlation_id") shouldBe correlationId
          auditEntries.get(1).get("correlation_id") shouldBe correlationId
        }
        "two requests were sent and the first one is user metadata request" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("username", "dev"))
          val userMetadataResponse = userMetadataManager.fetchMetadata()

          userMetadataResponse.responseCode should be (200)
          val correlationId = userMetadataResponse.responseJson("x-ror-logging-id").str

          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val getIndexResponse = indexManager.getIndex("twitter")
          getIndexResponse.responseCode shouldBe 200

          val auditEntries = auditIndexManager.auditIndexSearch().getEntries
          auditEntries.size shouldBe 2

          auditEntries.get(0).get("correlation_id") shouldBe correlationId
          auditEntries.get(1).get("correlation_id") shouldBe correlationId
        }
        "two metadata requests were sent, one with correlationId" in {
          def fetchMetadata(correlationId: Option[String] = None) = {
            val userMetadataManager = new RorApiManager(
              basicAuthClient("username", "dev"),
              additionalHeaders = correlationId.map(("x-ror-correlation-id", _)).toMap
            )
            userMetadataManager.fetchMetadata()
          }

          val response1 = fetchMetadata()
          response1.responseCode should be (200)
          val loggingId1 = response1.responseJson("x-ror-logging-id").str

          val response2 = fetchMetadata(correlationId = Some(loggingId1))
          response2.responseCode should be (200)
          val loggingId2 = response2.responseJson("x-ror-logging-id").str

          loggingId1 shouldNot be (loggingId2)

          val auditEntries = auditIndexManager.auditIndexSearch().getEntries
          auditEntries.size shouldBe 2

          auditEntries.get(0).get("correlation_id") shouldBe loggingId1
          auditEntries.get(1).get("correlation_id") shouldBe loggingId1
        }
      }
    }
    "not be audited" when {
      "rule 2 is matched" in {
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