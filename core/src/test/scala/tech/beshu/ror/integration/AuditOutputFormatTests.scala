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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.sink.{AuditDataStreamCreator, DataStreamAndIndexBasedAuditSinkServiceCreator}
import tech.beshu.ror.accesscontrol.audit.{AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.logging.AccessControlListLoggingDecorator
import tech.beshu.ror.audit.AuditEnvironmentContext
import tech.beshu.ror.audit.instances.BlockVerbosityAwareAuditLogSerializer
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, DataStreamService, IndexBasedAuditSinkService}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.TestsUtils.{fullDataStreamName, header, nes, testAuditEnvironmentContext}

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps

class AuditOutputFormatTests extends AnyWordSpec with BaseYamlLoadedAccessControlTest with MockFactory {

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
        val indexAuditSinkService = new MockedIndexAuditSinkService()
        val dataStreamAuditSinkService = new MockedDataStreamBasedAuditSinkService()
        val acl = auditedAcl(indexAuditSinkService, dataStreamAuditSinkService)
        val request = MockRequestContext.indices.withHeaders(
          header("x-forwarded-for", "192.168.0.1"), header("custom-one", "test")
        )

        acl.handleRegularRequest(request).runSyncUnsafe()

        def expectedJson(jsonString: String) = ujson.read(
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
             |  "path":"/_search",
             |  "indices":[],
             |  "@timestamp":"2020-01-01T00:00:00Z",
             |  "content_len_kb":0,
             |  "correlation_id":"${captureCorrelationId(jsonString)}",
             |  "processingMillis":${captureProcessingMillis(jsonString)},
             |  "xff":"192.168.0.1",
             |  "action":"indices:admin/get",
             |  "block":"default",
             |  "id":"mock",
             |  "content_len":0,
             |  "es_cluster_name": "testEsCluster",
             |  "es_node_name": "testEsNode"
             |}""".stripMargin
        )

        val (index, jsonStringFromIndex) = Await.result(indexAuditSinkService.result, 5 seconds)
        index.name.value should startWith("readonlyrest_audit-")
        ujson.read(jsonStringFromIndex) shouldBe expectedJson(jsonStringFromIndex)

        val (dataStream, jsonStringFromDataStream) = Await.result(dataStreamAuditSinkService.result, 5 seconds)
        dataStream shouldBe fullDataStreamName(NonEmptyString.unsafeFrom("readonlyrest_audit"))
        ujson.read(jsonStringFromDataStream) shouldBe expectedJson(jsonStringFromDataStream)
      }
      "is passed normally" in {
        val indexAuditSinkService = new MockedIndexAuditSinkService()
        val dataStreamAuditSinkService = new MockedDataStreamBasedAuditSinkService()
        val acl = auditedAcl(indexAuditSinkService, dataStreamAuditSinkService)
        val request = MockRequestContext.indices.withHeaders(
          header("X-Forwarded-For", "192.168.0.1"), header("Custom-One", "test")
        )

        acl.handleRegularRequest(request).runSyncUnsafe()

        def expectedJson(jsonString: String) = ujson.read(
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
             |  "path":"/_search",
             |  "indices":[],
             |  "@timestamp":"2020-01-01T00:00:00Z",
             |  "content_len_kb":0,
             |  "correlation_id":"${captureCorrelationId(jsonString)}",
             |  "processingMillis":${captureProcessingMillis(jsonString)},
             |  "xff":"192.168.0.1",
             |  "action":"indices:admin/get",
             |  "block":"default",
             |  "id":"mock",
             |  "content_len":0,
             |  "es_cluster_name": "testEsCluster",
             |  "es_node_name": "testEsNode"
             |}""".stripMargin
        )

        val (index, jsonStringFromIndex) = Await.result(indexAuditSinkService.result, 5 seconds)
        index.name.value should startWith("readonlyrest_audit-")
        ujson.read(jsonStringFromIndex) shouldBe expectedJson(jsonStringFromIndex)

        val (dataStream, jsonStringFromDataStream) = Await.result(dataStreamAuditSinkService.result, 5 seconds)
        dataStream shouldBe fullDataStreamName(nes("readonlyrest_audit"))
        ujson.read(jsonStringFromDataStream) shouldBe expectedJson(jsonStringFromDataStream)
      }
    }
  }

  private def auditedAcl(indexBasedAuditSinkService: IndexBasedAuditSinkService,
                         dataStreamBasedAuditSinkService: DataStreamBasedAuditSinkService) = {
    implicit val loggingContext: LoggingContext = LoggingContext(Set.empty)
    implicit val auditEnvironmentContext: AuditEnvironmentContext = testAuditEnvironmentContext
    val settings = AuditingTool.AuditSettings(
      NonEmptyList.of(
        AuditSink.Enabled(Config.EsIndexBasedSink(
          new BlockVerbosityAwareAuditLogSerializer,
          RorAuditIndexTemplate.default,
          AuditCluster.LocalAuditCluster
        )),
        AuditSink.Enabled(Config.EsDataStreamBasedSink(
          new BlockVerbosityAwareAuditLogSerializer,
          RorAuditDataStream.default,
          AuditCluster.LocalAuditCluster
        ))
      )
    )
    val auditingTool = AuditingTool.create(
      settings = settings,
      auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
        override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = dataStreamBasedAuditSinkService

        override def index(cluster: AuditCluster): IndexBasedAuditSinkService = indexBasedAuditSinkService
      }
    ).runSyncUnsafe().toOption.flatten.get
    new AccessControlListLoggingDecorator(acl, Some(auditingTool))
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

  private class MockedIndexAuditSinkService extends IndexBasedAuditSinkService {
    private val submittedIndexAndJson: Promise[(IndexName.Full, String)] = Promise()

    override def submit(indexName: IndexName.Full, documentId: String, jsonRecord: String)
                       (implicit requestId: RequestId): Unit = {
      submittedIndexAndJson.trySuccess(indexName, jsonRecord)
    }

    override def close(): Unit = ()

    def result: Future[(IndexName.Full, String)] = submittedIndexAndJson.future
  }

  private class MockedDataStreamBasedAuditSinkService extends DataStreamBasedAuditSinkService {
    private val submittedDataStreamAndJson: Promise[(DataStreamName.Full, String)] = Promise()

    override def submit(dataStreamName: DataStreamName.Full, documentId: String, jsonRecord: String)
                       (implicit requestId: RequestId): Unit = {
      submittedDataStreamAndJson.trySuccess(dataStreamName, jsonRecord)
    }

    override def close(): Unit = ()

    def result: Future[(DataStreamName.Full, String)] = submittedDataStreamAndJson.future

    private val mockedDataStreamService = mock[DataStreamService]
    (mockedDataStreamService.checkDataStreamExists(_: DataStreamName.Full))
      .expects(RorAuditDataStream.default.dataStream)
      .returning(Task.now(true))

    override def dataStreamCreator: AuditDataStreamCreator = AuditDataStreamCreator(NonEmptyList.one(mockedDataStreamService))
  }
}
