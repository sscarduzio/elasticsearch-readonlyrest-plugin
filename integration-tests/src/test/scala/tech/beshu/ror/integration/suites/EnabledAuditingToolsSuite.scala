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

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager, RorApiManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait EnabledAuditingToolsSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers
    with Eventually {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/enabled_auditing_tools/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = EnabledAuditingToolsSuite.nodeDataInitializer(),
      xPackSupport = false,
    )
  )

  private lazy val adminAuditIndexManager = new AuditIndexManager(adminClient, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    adminAuditIndexManager.truncate
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "Regular ES request" should {
    "be audited" when {
      "rule 1 is matched with logged user" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 200

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.force().jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          firstEntry("final_state").str shouldBe "ALLOWED"
          firstEntry("user").str shouldBe "username"
          firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
        }
      }
      "no rule is matched with username from auth header" in {
        val indexManager = new IndexManager(
          basicAuthClient("username", "wrong")
        )
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          firstEntry("final_state").str shouldBe "FORBIDDEN"
          firstEntry("user").str shouldBe "username"
        }
      }
      "no rule is matched with raw auth header as user" in {
        val indexManager = new IndexManager(tokenAuthClient("user_token"))
        val response = indexManager.getIndex("twitter")
        response.responseCode shouldBe 403

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          firstEntry("final_state").str shouldBe "FORBIDDEN"
          firstEntry("user").str shouldBe "user_token"
        }
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

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries(0)("correlation_id").str shouldBe correlationId
            auditEntries(1)("correlation_id").str shouldBe correlationId
          }
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

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries(0)("correlation_id").str shouldBe correlationId
            auditEntries(1)("correlation_id").str shouldBe correlationId
          }
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

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries.map(_ ("correlation_id").str).toSet shouldBe Set(loggingId1, loggingId2)
          }
        }
      }
    }
    "not be audited" when {
      "rule 2 is matched" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"))
        val response = indexManager.getIndex("facebook")
        response.responseCode shouldBe 200

        eventually {
          val auditEntriesResponse = adminAuditIndexManager.getEntries
          auditEntriesResponse.responseCode should be(404)
        }
      }
    }
  }

  "ROR audit event request" should {
    "be audited" when {
      "rule 3 is matched" when {
        "no JSON kay attribute from request body payload is defined in audit serializer" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"))

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }""")).force()

          response.responseCode shouldBe 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("event").str should be("logout")
          }
        }
        "user JSON key attribute from request doesn't override the defined in audit serializer" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"))

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "user": "unknown" }"""))

          response.responseCode shouldBe 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("user").str should be("username")
          }
        }
        "new JSON key attribute from request body as a JSON value" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"))

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": { "field1": 1, "fields2": "f2" } }"""))

          response.responseCode shouldBe 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("event") should be(ujson.read("""{ "field1": 1, "fields2": "f2" }"""))
          }
        }
      }
    }
    "not be audited" when {
      "admin rule is matched" in {
        val rorApiManager = new RorApiManager(adminClient)

        val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }"""))
        response.responseCode shouldBe 204

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
      "request JSON is malformed" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"))

        val response = rorApiManager.sendAuditEvent(ujson.read("""[]"""))
        response.responseCode shouldBe 400

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
      "request JSON is too large (>5KB)" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"))

        val response = rorApiManager.sendAuditEvent(ujson.read(s"""{ "event": "${Stream.continually("!").take(5000).mkString}" }"""))
        response.responseCode shouldBe 413

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
    }
  }
}

object EnabledAuditingToolsSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}