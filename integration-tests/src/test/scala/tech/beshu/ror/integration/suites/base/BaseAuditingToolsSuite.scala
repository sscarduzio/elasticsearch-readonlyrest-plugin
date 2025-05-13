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

import better.files.File
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseSingleNodeEsClusterTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, IndexManager, RorApiManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.misc.Resources.getResourcePath
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.util.UUID

class BaseAuditingToolsSuite
  extends AnyWordSpec
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterEach
    with CustomScalaTestMatchers
    with Eventually
    with ScalaCheckPropertyChecks
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  override implicit val rorConfigFileName: String = "/enabled_auditing_tools/readonlyrest.yml"

  private lazy val adminAuditIndexManager = new AuditIndexManager(adminClient, esVersionUsed, "audit_index")

  override def beforeEach(): Unit = {
    super.beforeEach()
    adminAuditIndexManager.truncate
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  lazy val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)
  lazy val adminRorApiManager = new RorApiManager(adminClient, esVersionUsed)

  private lazy val simpleSyntaxTestParams = Table[String, String, JSON => Unit](
    ("name",                           "config",                                           "assertionForEveryAuditEntry"),
    ("LocalClusterAuditingToolsSuite", config("/enabled_auditing_tools/readonlyrest.yml"), _ => ()),
  )

  private def config(path: String) = File(getResourcePath(path)).contentAsString

  forAll(simpleSyntaxTestParams) { (name, config, assertForEveryAuditEntry) =>
  s"$name - Regular ES request" should {
    "be audited" when {
      "rule 1 is matched with logged user" in {
        adminRorApiManager.updateRorTestConfig(config)
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.force().jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          firstEntry("final_state").str shouldBe "ALLOWED"
          firstEntry("user").str shouldBe "username"
          firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
          assertForEveryAuditEntry(firstEntry)
        }
      }
      "no rule is matched with username from auth header" in {
        adminRorApiManager.updateRorTestConfig(config)
        val indexManager = new IndexManager(
          basicAuthClient("username", "wrong"), esVersionUsed
        )
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          firstEntry("final_state").str shouldBe "FORBIDDEN"
          firstEntry("user").str shouldBe "username"
          assertForEveryAuditEntry(firstEntry)
        }
      }
      "no rule is matched with raw auth header as user" in {
        adminRorApiManager.updateRorTestConfig(config)
        val indexManager = new IndexManager(tokenAuthClient("user_token"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        eventually {
          val auditEntries = adminAuditIndexManager.getEntries.jsons
          auditEntries.size shouldBe 1

          val firstEntry = auditEntries(0)
          println(firstEntry)
          firstEntry("final_state").str shouldBe "FORBIDDEN"
          firstEntry("user").str shouldBe "user_token"
          assertForEveryAuditEntry(firstEntry)
        }
      }
      "rule 1 is matched" when {
        "two requests were sent and correlationId is the same for both of them" in {
          adminRorApiManager.updateRorTestConfig(config)
          val correlationId = UUID.randomUUID().toString
          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            esVersionUsed,
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val response1 = indexManager.getIndex("twitter")
          response1 should have statusCode 200

          val response2 = indexManager.getIndex("twitter")
          response2 should have statusCode 200

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries(0)("correlation_id").str shouldBe correlationId
            assertForEveryAuditEntry(auditEntries(0))
            auditEntries(1)("correlation_id").str shouldBe correlationId
            assertForEveryAuditEntry(auditEntries(1))
          }
        }
        "two requests were sent and the first one is user metadata request" in {
          adminRorApiManager.updateRorTestConfig(config)
          val userMetadataResponse = rorApiManager.fetchMetadata()

          userMetadataResponse should have statusCode 200
          val correlationId = userMetadataResponse.responseJson("x-ror-correlation-id").str

          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            esVersionUsed,
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val getIndexResponse = indexManager.getIndex("twitter")
          getIndexResponse should have statusCode 200

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries(0)("correlation_id").str shouldBe correlationId
            assertForEveryAuditEntry(auditEntries(0))
            auditEntries(1)("correlation_id").str shouldBe correlationId
            assertForEveryAuditEntry(auditEntries(1))
          }
        }
        "two metadata requests were sent, one with correlationId" in {
          adminRorApiManager.updateRorTestConfig(config)
          def fetchMetadata(correlationId: Option[String]) = {
            rorApiManager.fetchMetadata(correlationId = correlationId)
          }

          val correlationId = UUID.randomUUID().toString
          val response1 = fetchMetadata(correlationId = Some(correlationId))
          response1 should have statusCode 200
          val loggingId1 = response1.responseJson("x-ror-correlation-id").str

          val response2 = fetchMetadata(correlationId = Some(correlationId))
          response2 should have statusCode 200
          val loggingId2 = response2.responseJson("x-ror-correlation-id").str

          loggingId1 should be(loggingId2)

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size shouldBe 2

            auditEntries.map(_("correlation_id").str).toSet shouldBe Set(loggingId1, loggingId2)
            auditEntries.map(assertForEveryAuditEntry)
          }
        }
      }
    }
    "not be audited" when {
      "rule 2 is matched" in {
        adminRorApiManager.updateRorTestConfig(config)
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        val response = indexManager.getIndex("facebook")
        response should have statusCode 200

        eventually {
          val auditEntriesResponse = adminAuditIndexManager.getEntries
          auditEntriesResponse should have statusCode 404
        }
      }
    }
  }

  "ROR audit event request" should {
    "be audited" when {
      "rule 3 is matched" when {
        "no JSON kay attribute from request body payload is defined in audit serializer" in {
          adminRorApiManager.updateRorTestConfig(config)
          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }""")).force()

          response should have statusCode 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("event").str should be("logout")
            assertForEveryAuditEntry(auditEntries(0))
          }
        }
        "user JSON key attribute from request doesn't override the defined in audit serializer" in {
          adminRorApiManager.updateRorTestConfig(config)
          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "user": "unknown" }"""))

          response should have statusCode 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("user").str should be("username")
            assertForEveryAuditEntry(auditEntries(0))
          }
        }
        "new JSON key attribute from request body as a JSON value" in {
          adminRorApiManager.updateRorTestConfig(config)
          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": { "field1": 1, "fields2": "f2" } }"""))

          response should have statusCode 204

          eventually {
            val auditEntries = adminAuditIndexManager.getEntries.jsons
            auditEntries.size should be(1)
            auditEntries(0)("event") should be(ujson.read("""{ "field1": 1, "fields2": "f2" }"""))
            assertForEveryAuditEntry(auditEntries(0))
          }
        }
      }
    }
    "not be audited" when {
      "admin rule is matched" in {
        adminRorApiManager.updateRorTestConfig(config)
        val response = adminRorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }"""))
        response should have statusCode 204

        eventually {
          val entriesResult = adminAuditIndexManager.getEntries
          entriesResult should have statusCode 404
        }
      }
      "request JSON is malformed" in {
        adminRorApiManager.updateRorTestConfig(config)
        val response = rorApiManager.sendAuditEvent(ujson.read("""[]"""))
        response should have statusCode 400
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
          entriesResult should have statusCode 404
        }
      }
      "request JSON is too large (>5KB)" in {
        adminRorApiManager.updateRorTestConfig(config)
        val response = rorApiManager.sendAuditEvent(ujson.read(s"""{ "event": "${LazyList.continually("!").take(5000).mkString}" }"""))
        response should have statusCode 413
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
          entriesResult should have statusCode 404
        }
      }
    }
  }
  }
}
