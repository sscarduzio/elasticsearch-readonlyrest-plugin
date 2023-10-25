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
package tech.beshu.ror.integration

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate}
import tech.beshu.ror.accesscontrol.logging.AccessControlLoggingDecorator
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.header

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps

class AuditOutputFormatTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest {

  override protected def configYaml: String =
    """
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "CONTAINER ADMIN"
      |    type: allow
      |    auth_key: admin:container
      |
      |  - name: "User 1"
      |    type: allow
      |    auth_key: user1:dev
    """.stripMargin

  private implicit val clock: Clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00.00Z"), ZoneId.of("UTC"))

  "An X-Forwarded-For header" should {
    "be present as XFF in audit" when {
      "is passed using lower cases" in {
        val auditSinkService = new MockedAuditSinkService()
        val acl = auditedAcl(auditSinkService)
        val request = MockRequestContext.indices.copy(headers = Set(
          header("x-forwarded-for", "192.168.0.1"),
          header("custom-one", "test")
        ))

        acl.handleRegularRequest(request).runSyncUnsafe()

        val (index, jsonString) = Await.result(auditSinkService.result, 5 seconds)
        index should startWith("readonlyrest_audit-")
        ujson.read(jsonString) should be(ujson.read(
          s"""{
             |  "headers":["x-forwarded-for", "custom-one"],
             |  "acl_history":"[CONTAINER ADMIN-> RULES:[auth_key->false]], [User 1-> RULES:[auth_key->false]]",
             |  "origin":"localhost",
             |  "match":false,
             |  "final_state":"FORBIDDEN",
             |  "destination":"localhost",
             |  "task_id":0,
             |  "type":"default-type",
             |  "req_method":"GET",
             |  "path":"_search",
             |  "indices":[],
             |  "@timestamp":"2020-01-01T00:00:00Z",
             |  "content_len_kb":0,
             |  "correlation_id":"${captureCorrelationId(jsonString)}",
             |  "processingMillis":${captureProcessingMillis(jsonString)},
             |  "xff":"192.168.0.1",
             |  "action":"indices:admin/get",
             |  "block":"default",
             |  "id":"mock",
             |  "content_len":0
             |}""".stripMargin
        ))
      }
      "is passed normally" in {
        val auditSinkService = new MockedAuditSinkService()
        val acl = auditedAcl(auditSinkService)
        val request = MockRequestContext.indices.copy(headers = Set(
          header("X-Forwarded-For", "192.168.0.1"),
          header("Custom-One", "test")
        ))

        acl.handleRegularRequest(request).runSyncUnsafe()

        val (index, jsonString) = Await.result(auditSinkService.result, 5 seconds)
        index should startWith("readonlyrest_audit-")
        ujson.read(jsonString) should be(ujson.read(
          s"""{
             |  "headers":["X-Forwarded-For", "Custom-One"],
             |  "acl_history":"[CONTAINER ADMIN-> RULES:[auth_key->false]], [User 1-> RULES:[auth_key->false]]",
             |  "origin":"localhost",
             |  "match":false,
             |  "final_state":"FORBIDDEN",
             |  "destination":"localhost",
             |  "task_id":0,
             |  "type":"default-type",
             |  "req_method":"GET",
             |  "path":"_search",
             |  "indices":[],
             |  "@timestamp":"2020-01-01T00:00:00Z",
             |  "content_len_kb":0,
             |  "correlation_id":"${captureCorrelationId(jsonString)}",
             |  "processingMillis":${captureProcessingMillis(jsonString)},
             |  "xff":"192.168.0.1",
             |  "action":"indices:admin/get",
             |  "block":"default",
             |  "id":"mock",
             |  "content_len":0
             |}""".stripMargin
        ))
      }
    }
  }

  private def auditedAcl(auditSinkService: AuditSinkService) = {
    implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)
    val settings = AuditingTool.Settings(
      NonEmptyList.of(
        AuditSink.Enabled(Config.EsIndexBasedSink(
          new DefaultAuditLogSerializer,
          RorAuditIndexTemplate.default,
          AuditCluster.LocalAuditCluster
        ))
      )
    )
    val auditingTool = AuditingTool.create(
      settings = settings,
      auditSinkServiceCreator = _ => auditSinkService
    ).get
    new AccessControlLoggingDecorator(acl, Some(auditingTool))
  }

  private def captureProcessingMillis(jsonString: String) = {
    "\"processingMillis\":(\\d*),".r
      .findFirstMatchIn(jsonString)
      .getOrElse(throw new IllegalStateException("no processingMillis pattern matched"))
      .group(1).toLong
  }

  private def captureCorrelationId(jsonString: String) = {
    "\"correlation_id\":\"((\\d|\\w|-)*)\",".r
      .findFirstMatchIn(jsonString)
      .getOrElse(throw new IllegalStateException("no correlation_id pattern matched"))
      .group(1)
  }

  private class MockedAuditSinkService extends AuditSinkService {
    private val submittedIndexAndJson: Promise[(String, String)] = Promise()

    override def submit(indexName: String, documentId: String, jsonRecord: String): Unit = {
      submittedIndexAndJson.trySuccess(indexName, jsonRecord)
    }

    override def close(): Unit = ()

    def result: Future[(String, String)] = submittedIndexAndJson.future
  }
}
