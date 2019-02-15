package tech.beshu.ror.unit.acl.logging

import java.time._
import java.time.format.DateTimeFormatter

import cats.data.{NonEmptyList, NonEmptySet}
import com.softwaremill.sttp.Method
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.{Block, RequestContextInitiatedBlockContext}
import tech.beshu.ror.acl.blocks.rules.MethodsRule
import tech.beshu.ror.acl.logging.ResponseContext._
import tech.beshu.ror.acl.logging.{AuditSink, AuditingTool}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.mocks.MockRequestContext
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import tech.beshu.ror.acl.blocks.Block.{Policy, Verbosity}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

class AuditingToolTests extends WordSpec with MockFactory {

  "Auditing tool used with DefaultAuditLogSerializer" should {
    "not submit any audit entry" when {
      "request was allowed and verbosity level was INFO" in {
        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          mock[AuditSink]
        )
        auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
      }
      "custom serializer throws exception" in {
        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            throwingAuditLogSerializer
          ),
          mock[AuditSink]
        )
        an [IllegalArgumentException] should be thrownBy {
          auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
        }
      }
    }
    "submit audit entry" when {
      "request was allowed and verbosity level was different than INFO" in {
        val auditSink = mock[AuditSink]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          auditSink
        )

        auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Error)).runSyncUnsafe()
      }
      "request was matched by forbidden rule" in {
        val auditSink = mock[AuditSink]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          auditSink
        )

        val requestContext = MockRequestContext.default.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = ForbiddenBy(
          requestContext,
          new Block(
            Block.Name("mock-block"),
            Block.Policy.Forbid,
            Block.Verbosity.Info,
            NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))))
          ),
          RequestContextInitiatedBlockContext.fromRequestContext(requestContext),
          Vector.empty
        )

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
      "request was forbidden" in {
        val auditSink = mock[AuditSink]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          auditSink
        )

        val requestContext = MockRequestContext.default.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = Forbidden(requestContext, Vector.empty)

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
      "request was finished with error" in {
        val auditSink = mock[AuditSink]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          auditSink
        )

        val requestContext = MockRequestContext.default.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = Errored(requestContext, new Exception("error"))

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
      "request was not found" in {
        val auditSink = mock[AuditSink]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            DateTimeFormatter.ofPattern("'test_'yyyy-MM-dd").withZone(ZoneId.of("UTC")),
            new DefaultAuditLogSerializer
          ),
          auditSink
        )

        val requestContext = MockRequestContext.default.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = NotFound(requestContext, new Exception("not found"))

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
    }
  }

  private val someday = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  private def createAllowedResponseContext(policy: Block.Policy, verbosity: Block.Verbosity) = {
    val requestContext = MockRequestContext.default.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
    Allowed(
      requestContext,
      new Block(
        Block.Name("mock-block"),
        policy,
        verbosity,
        NonEmptyList.one(new MethodsRule(MethodsRule.Settings(NonEmptySet.one(Method.GET))))
      ),
      RequestContextInitiatedBlockContext.fromRequestContext(requestContext),
      Vector.empty
    )
  }

  private implicit val fixedClock: Clock = Clock.fixed(someday.toInstant, someday.getZone)

  private val throwingAuditLogSerializer = new AuditLogSerializer {
    override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
      throw new IllegalArgumentException("sth went wrong")
    }
  }

}
