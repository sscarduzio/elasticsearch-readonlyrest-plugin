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

import java.time._
import cats.data.{NonEmptyList, NonEmptySet}
import com.softwaremill.sttp.Method
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.{Policy, Verbosity}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.http.MethodsRule
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, RorAuditIndexTemplate}
import tech.beshu.ror.accesscontrol.logging.AuditingTool
import tech.beshu.ror.accesscontrol.logging.ResponseContext._
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}
import tech.beshu.ror.es.AuditSinkService
import tech.beshu.ror.mocks.MockRequestContext

class AuditingToolTests extends AnyWordSpec with MockFactory {
  import tech.beshu.ror.utils.TestsUtils.loggingContext

  "Auditing tool used with DefaultAuditLogSerializer" should {
    "not submit any audit entry" when {
      "request was allowed and verbosity level was ERROR" in {
        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            new DefaultAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          mock[AuditSinkService]
        )
        auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Error)).runSyncUnsafe()
      }
      "custom serializer throws exception" in {
        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            throwingAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          mock[AuditSinkService]
        )
        an [IllegalArgumentException] should be thrownBy {
          auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
        }
      }
    }
    "submit audit entry" when {
      "request was allowed and verbosity level was INFO" in {
        val auditSink = mock[AuditSinkService]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            new DefaultAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          auditSink
        )

        auditingTool.audit(createAllowedResponseContext(Policy.Allow, Verbosity.Info)).runSyncUnsafe()
      }
      "request was matched by forbidden rule" in {
        val auditSink = mock[AuditSinkService]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            new DefaultAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          auditSink
        )

        val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = ForbiddenBy(
          requestContext,
          new Block(
            Block.Name("mock-block"),
            Block.Policy.Forbid,
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

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            new DefaultAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          auditSink
        )

        val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = Forbidden(requestContext, Vector.empty)

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
      "request was finished with error" in {
        val auditSink = mock[AuditSinkService]
        (auditSink.submit _).expects("test_2018-12-31", "mock-1", *).returning(())

        val auditingTool = new AuditingTool(
          AuditingTool.Settings(
            RorAuditIndexTemplate.from("'test_'yyyy-MM-dd").right.get,
            new DefaultAuditLogSerializer,
            AuditCluster.LocalAuditCluster
          ),
          auditSink
        )

        val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
        val responseContext = Errored(requestContext, new Exception("error"))

        auditingTool.audit(responseContext).runSyncUnsafe()
      }
    }
  }

  private val someday = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  private def createAllowedResponseContext(policy: Block.Policy, verbosity: Block.Verbosity) = {
    val requestContext = MockRequestContext.indices.copy(timestamp = someday.toInstant, id = RequestContext.Id("mock-1"))
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

  private val throwingAuditLogSerializer = new AuditLogSerializer {
    override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
      throw new IllegalArgumentException("sth went wrong")
    }
  }

}
