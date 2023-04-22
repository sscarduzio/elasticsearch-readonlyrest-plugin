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
package tech.beshu.ror.integration.suites.base

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.SingleClientSupport
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, IndexManager, RorApiManager}

import java.util.UUID

trait BaseAuditingToolsSuite
  extends AnyWordSpec
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterEach
    with Matchers
    with Eventually {
  this: EsClusterProvider =>

  protected def destNodeClientProvider: ClientProvider

  private lazy val adminAuditIndexManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    adminAuditIndexManager.truncate
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "Regular ES request" should {
    "be audited" when {
      "rule 1 is matched with logged user" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
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
          basicAuthClient("username", "wrong"), esVersionUsed
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
        val indexManager = new IndexManager(tokenAuthClient("user_token"), esVersionUsed)
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
            esVersionUsed,
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
          val userMetadataManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)
          val userMetadataResponse = userMetadataManager.fetchMetadata()

          userMetadataResponse.responseCode should be(200)
          val correlationId = userMetadataResponse.responseJson("x-ror-correlation-id").str

          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            esVersionUsed,
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
          def fetchMetadata(correlationId: Option[String]) = {
            val userMetadataManager = new RorApiManager(
              client = basicAuthClient("username", "dev"),
              esVersion = esVersionUsed
            )
            userMetadataManager.fetchMetadata(correlationId = correlationId)
          }

          val correlationId = UUID.randomUUID().toString
          val response1 = fetchMetadata(correlationId = Some(correlationId))
          response1.responseCode should be(200)
          val loggingId1 = response1.responseJson("x-ror-correlation-id").str

          val response2 = fetchMetadata(correlationId = Some(correlationId))
          response2.responseCode should be(200)
          val loggingId2 = response2.responseJson("x-ror-correlation-id").str

          loggingId1 should be(loggingId2)

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries.map(_("correlation_id").str).toSet shouldBe Set(loggingId1, loggingId2)
          }
        }
      }
    }
    "not be audited" when {
      "rule 2 is matched" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
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
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }""")).force()

          response.responseCode shouldBe 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("event").str should be("logout")
          }
        }
        "user JSON key attribute from request doesn't override the defined in audit serializer" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "user": "unknown" }"""))

          response.responseCode shouldBe 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("user").str should be("username")
          }
        }
        "new JSON key attribute from request body as a JSON value" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

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
        val rorApiManager = new RorApiManager(adminClient, esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }"""))
        response.responseCode shouldBe 204

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
      "request JSON is malformed" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read("""[]"""))
        response.responseCode shouldBe 400
        response.responseJson should be(ujson.read(
          """
            |{
            |  "error":{
            |    "root_cause":[
            |      {
            |        "type":"audit_event_bad_request",
            |        "reason":"Content malformed"
            |      }
            |    ],
            |    "type":"audit_event_bad_request",
            |    "reason":"Content malformed"
            |  },
            |  "status":400
            |}
          """.stripMargin))

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
      "request JSON is too large (>5KB)" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read(s"""{ "event": "${LazyList.continually("!").take(5000).mkString}" }"""))
        response.responseCode shouldBe 413
        response.responseJson should be(ujson.read(
          """
            |{
            |  "error":{
            |    "root_cause":[
            |      {
            |        "type":"audit_event_request_payload_too_large",
            |        "reason":"Max request content allowed = 40.0KB"
            |      }
            |    ],
            |    "type":"audit_event_request_payload_too_large",
            |    "reason":"Max request content allowed = 40.0KB"
            |  },
            |  "status":413
            |}
          """.stripMargin))

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult.responseCode should be(404)
        }
      }
    }
  }
}
