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
package tech.beshu.ror.unit.acl.logging

import better.files.*
import cats.data.{NonEmptyList, NonEmptySet}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.sink.{AuditDataStreamCreator, DataStreamAndIndexBasedAuditSinkServiceCreator}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.{Policy, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, DataStreamName, RorAuditDataStream, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.logging.ResponseContext.*
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.{DataStreamBasedAuditSinkService, DataStreamService, IndexBasedAuditSinkService}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{fullDataStreamName, fullIndexName, nes, testAuditEnvironmentContext, unsafeNes}

import java.time.*
import java.util.UUID
import scala.annotation.nowarn

class AuditingToolTests extends AnyWordSpec with MockFactory with BeforeAndAfterAll {

  import tech.beshu.ror.utils.TestsUtils.loggingContext

  private val auditLogFile = File("/tmp/ror/audit_logs/test_audit.log")

  "Auditing tool" when {
    "used with DefaultAuditLogSerializer" when {
      "es index sink is used" should {
        "not submit any audit entry" when {
          "request was allowed and verbosity level was ERROR" in {
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService =
                  mockedDataStreamBasedAuditSinkService

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = mock[IndexBasedAuditSinkService]
              }
            ).runSyncUnsafe().toOption.flatten.get
            auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Error), testAuditEnvironmentContext).runSyncUnsafe()
          }
          "custom serializer throws exception" in {
            val auditingTool = AuditingTool.create(
              settings = auditSettings(throwingAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService =
                  mockedDataStreamBasedAuditSinkService

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = mock[IndexBasedAuditSinkService]
              }
            ).runSyncUnsafe().toOption.flatten.get
            an[IllegalArgumentException] should be thrownBy {
              auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info), testAuditEnvironmentContext).runSyncUnsafe()
            }
          }
        }
        "submit audit entry" when {
          "request was allowed and verbosity level was INFO" in {
            val indexAuditSink = mock[IndexBasedAuditSinkService]
            (indexAuditSink.submit _).expects(fullIndexName("test_2018-12-31"), "mock-1", *).returning(())
            val dataStreamAuditSink = mockedDataStreamBasedAuditSinkService
            (dataStreamAuditSink.submit _).expects(fullDataStreamName("test_ds"), "mock-1", *).returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = dataStreamAuditSink

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = indexAuditSink
              }
            ).runSyncUnsafe().toOption.flatten.get
            auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info), testAuditEnvironmentContext).runSyncUnsafe()
          }
          "request was matched by forbidden rule" in {
            val indexAuditSink = mock[IndexBasedAuditSinkService]
            (indexAuditSink.submit _).expects(fullIndexName("test_2018-12-31"), "mock-1", *).returning(())
            val dataStreamAuditSink = mockedDataStreamBasedAuditSinkService
            (dataStreamAuditSink.submit _).expects(fullDataStreamName("test_ds"), "mock-1", *).returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = dataStreamAuditSink

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = indexAuditSink
              }
            ).runSyncUnsafe().toOption.flatten.get

            val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
            val responseContext = ForbiddenBy(
              requestContext,
              new Block(
                Block.Name("mock-block"),
                Block.Policy.Forbid(),
                Block.Verbosity.Info,
                NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))))
              ),
              GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty),
              Vector.empty
            )

            auditingTool.audit(responseContext, testAuditEnvironmentContext).runSyncUnsafe()
          }
          "request was forbidden" in {
            val indexAuditSink = mock[IndexBasedAuditSinkService]
            (indexAuditSink.submit _).expects(fullIndexName("test_2018-12-31"), "mock-1", *).returning(())
            val dataStreamAuditSink = mockedDataStreamBasedAuditSinkService
            (dataStreamAuditSink.submit _).expects(fullDataStreamName("test_ds"), "mock-1", *).returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = dataStreamAuditSink

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = indexAuditSink
              }
            ).runSyncUnsafe().toOption.flatten.get

            val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
            val responseContext = Forbidden(requestContext, Vector.empty)

            auditingTool.audit(responseContext, testAuditEnvironmentContext).runSyncUnsafe()
          }
          "request was finished with error" in {
            val indexAuditSink = mock[IndexBasedAuditSinkService]
            (indexAuditSink.submit _).expects(fullIndexName("test_2018-12-31"), "mock-1", *).returning(())
            val dataStreamAuditSink = mockedDataStreamBasedAuditSinkService
            (dataStreamAuditSink.submit _).expects(fullDataStreamName("test_ds"), "mock-1", *).returning(())
            @nowarn("cat=deprecation")
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
                override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = dataStreamAuditSink

                override def index(cluster: AuditCluster): IndexBasedAuditSinkService = indexAuditSink
              }
            ).runSyncUnsafe().toOption.flatten.get

            val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
            val responseContext = Errored(requestContext, new Exception("error"))

            auditingTool.audit(responseContext, testAuditEnvironmentContext).runSyncUnsafe()
          }
        }
      }
      "log sink is used" should {
        "saved audit log to file defined in log4j config" in {
          @nowarn("cat=deprecation")
          val auditingTool = AuditingTool.create(
            settings = AuditSettings(
              NonEmptyList.of(
                AuditSink.Enabled(Config.LogBasedSink(
                  new DefaultAuditLogSerializer,
                  RorAuditLoggerName.default
                ))
              )
            ),
            auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
              override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = mock[DataStreamBasedAuditSinkService]

              override def index(cluster: AuditCluster): IndexBasedAuditSinkService = mock[IndexBasedAuditSinkService]
            }
          ).runSyncUnsafe().toOption.flatten.get

          val requestContextId = RequestContext.Id.fromString(UUID.randomUUID().toString)
          val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = requestContextId)
          val responseContext = Errored(requestContext, new Exception("error"))

          auditLogFile.overwrite("")

          auditingTool.audit(responseContext, testAuditEnvironmentContext).runSyncUnsafe()
          val logFileContent = auditLogFile.contentAsString

          logFileContent should include(requestContextId.value)
        }
      }
    }
    "no enabled outputs in settings" should {
      "be disabled" in {
        val creationResult = AuditingTool.create(
          settings = AuditSettings(NonEmptyList.of(AuditSink.Disabled, AuditSink.Disabled, AuditSink.Disabled)),
          auditSinkServiceCreator = new DataStreamAndIndexBasedAuditSinkServiceCreator {
            override def dataStream(cluster: AuditCluster): DataStreamBasedAuditSinkService = mock[DataStreamBasedAuditSinkService]

            override def index(cluster: AuditCluster): IndexBasedAuditSinkService = mock[IndexBasedAuditSinkService]
          }
        ).runSyncUnsafe()
        creationResult should be(Right(None))
      }
    }
  }

  private def auditSettings(serializer: AuditLogSerializer) = AuditSettings(NonEmptyList.of(
    AuditSink.Enabled(Config.EsIndexBasedSink(
      serializer,
      RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").toOption.get,
      AuditCluster.LocalAuditCluster
    )),
    AuditSink.Enabled(Config.EsDataStreamBasedSink(
      serializer,
      RorAuditDataStream.from("test_ds").toOption.get,
      AuditCluster.LocalAuditCluster
    ))
  ))

  private lazy val someday = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  private def createAllowedResponseContext(policy: Block.Policy, verbosity: Block.Verbosity) = {
    val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
    AllowedBy(
      requestContext,
      new Block(
        Block.Name("mock-block"),
        policy,
        verbosity,
        NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))))
      ),
      GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty),
      Vector.empty
    )
  }

  private implicit val fixedClock: Clock = Clock.fixed(someday.toInstant, someday.getZone)

  private lazy val throwingAuditLogSerializer = new AuditLogSerializer {
    override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
      throw new IllegalArgumentException("sth went wrong")
    }
  }

  private def mockedDataStreamBasedAuditSinkService: DataStreamBasedAuditSinkService & reflect.Selectable = {
    val mockedDataStreamService = mock[DataStreamService]

    (mockedDataStreamService.checkDataStreamExists(_: DataStreamName.Full))
      .expects(fullDataStreamName(nes("test_ds")))
      .returning(Task.now(true))

    val mockedService = mock[DataStreamBasedAuditSinkService]
    (() => mockedService.dataStreamCreator)
      .expects()
      .returns(AuditDataStreamCreator(NonEmptyList.one(mockedDataStreamService)))

    mockedService
  }

}
