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

import better.files._
import cats.data.{NonEmptyList, NonEmptySet}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.Config
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.{Policy, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate, RorAuditLoggerName}
import tech.beshu.ror.accesscontrol.logging.ResponseContext._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import java.time._
import java.util.UUID

class AuditingToolTests extends AnyWordSpec with MockFactory with BeforeAndAfterAll {

  import tech.beshu.ror.utils.TestsUtils.loggingContext

  private val auditLogFile = File("/tmp/ror/audit_logs/test_audit.log")

  "Auditing tool" when {
    "used with DefaultAuditLogSerializer" when {
      "es index sink is used" should {
        "not submit any audit entry" when {
          "request was allowed and verbosity level was ERROR" in {
            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = _ => mock[AuditSinkService]
            ).get
            auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Error)).runSyncUnsafe()
          }
          "custom serializer throws exception" in {
            val auditingTool = AuditingTool.create(
              settings = auditSettings(throwingAuditLogSerializer),
              auditSinkServiceCreator = _ => mock[AuditSinkService]
            ).get
            an[IllegalArgumentException] should be thrownBy {
              auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
            }
          }
        }
        "submit audit entry" when {
          "request was allowed and verbosity level was INFO" in {
            val auditSink = mock[AuditSinkService]
            (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = _ => auditSink
            ).get
            auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
          }
          "request was matched by forbidden rule" in {
            val auditSink = mock[AuditSinkService]
            (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = _ => auditSink
            ).get

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

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
          "request was forbidden" in {
            val auditSink = mock[AuditSinkService]
            (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = _ => auditSink
            ).get

            val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
            val responseContext = Forbidden(requestContext, Vector.empty)

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
          "request was finished with error" in {
            val auditSink = mock[AuditSinkService]
            (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

            val auditingTool = AuditingTool.create(
              settings = auditSettings(new DefaultAuditLogSerializer),
              auditSinkServiceCreator = _ => auditSink
            ).get

            val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id.fromString("mock-1"))
            val responseContext = Errored(requestContext, new Exception("error"))

            auditingTool.audit(responseContext).runSyncUnsafe()
          }
        }
      }
      "log sink is used" should {
        "saved audit log to file defined in log4j config" in {
          val auditingTool = AuditingTool.create(
            settings = Settings(
              NonEmptyList.of(
                AuditSink.Enabled(Config.LogBasedSink(
                  new DefaultAuditLogSerializer,
                  RorAuditLoggerName.default
                ))
              )
            ),
            auditSinkServiceCreator = _ => mock[AuditSinkService]
          ).get

          val requestContextId = RequestContext.Id.fromString(UUID.randomUUID().toString)
          val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = requestContextId)
          val responseContext = Errored(requestContext, new Exception("error"))

          auditLogFile.overwrite("")

          auditingTool.audit(responseContext).runSyncUnsafe()
          val logFileContent = auditLogFile.contentAsString

          logFileContent should include(requestContextId.value)
        }
      }
    }
    "no enabled outputs in settings" should {
      "be disabled" in {
        val creationResult = AuditingTool.create(
          Settings(NonEmptyList.of(AuditSink.Disabled, AuditSink.Disabled, AuditSink.Disabled)),
          _ => mock[AuditSinkService]
        )
        creationResult should be(None)
      }
    }
  }

  private def auditSettings(serializer: AuditLogSerializer) = Settings(NonEmptyList.of(
    AuditSink.Enabled(Config.EsIndexBasedSink(
      serializer,
      RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").toOption.get,
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

}
