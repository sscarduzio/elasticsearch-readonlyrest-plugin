package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.TestsUtils.BlockContextAssertion
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.{BaseSpecializedIndicesRule, SnapshotsRule}
import tech.beshu.ror.acl.blocks.{BlockContext, Const, RequestContextInitiatedBlockContext, Value}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext

import scala.concurrent.duration._
import scala.language.postfixOps

class SnapshotsRuleTests
  extends WordSpec with Inside with BlockContextAssertion[BaseSpecializedIndicesRule.Settings] {

  "A SnapshotsRule" should {
    "match" when {
      "request action doesn't contain 'snapshot'" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("snapshot1"))),
          requestAction = Action("cluster:admin/rradmin/refreshsettings"),
          requestSnapshots = Set(IndexName("snapshot1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains *" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("snapshot1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains _all" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("_all"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("snapshot1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("public-asd"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("public-asd"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured several snapshots and several snapshots in request" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.of(Const(IndexName("public-*")), Const(IndexName("n"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"), IndexName("q")),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext(
            snapshots = Set(IndexName("public-asd"))
          )(blockContext)
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd")),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("x_public-asd"))
        )
      }
      "write request with configured several snapshots and several snapshots in request" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.of(Const(IndexName("public-*")), Const(IndexName("n"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"), IndexName("q"))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"), IndexName("q"))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(IndexName("public-asd"), IndexName("q")),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredSnapshots: NonEmptySet[Value[IndexName]],
                              requestAction: Action,
                              requestSnapshots: Set[IndexName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredSnapshots: NonEmptySet[Value[IndexName]],
                                 requestAction: Action,
                                 requestSnapshots: Set[IndexName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredSnapshots: NonEmptySet[Value[IndexName]],
                         requestAction: Action,
                         requestSnapshots: Set[IndexName],
                         readonlyRequest: Boolean,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new SnapshotsRule(BaseSpecializedIndicesRule.Settings(configuredSnapshots))
    val requestContext = MockRequestContext(action = requestAction, snapshots = requestSnapshots, isReadOnlyRequest = readonlyRequest)
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected)
    }
  }

}
