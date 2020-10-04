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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.SnapshotsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Action, SnapshotName}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps

class SnapshotsRuleTests extends WordSpec with Inside {

  "A SnapshotsRule" should {
    "match" when {
      "allowed snapshots set contains *" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("snapshot1".nonempty))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("snapshot1".nonempty)))
        }
      }
      "allowed snapshots set contains _all" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("_all".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("snapshot1".nonempty))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("snapshot1".nonempty)))
        }
      }
      "readonly request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-asd".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty)),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd".nonempty)))
        }
      }
      "readonly request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty)),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd".nonempty)))
        }
      }
      "write request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-asd".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd".nonempty)))
        }
      }
      "write request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd".nonempty)))
        }
      }
      "readonly request with configured several snapshots and several snapshots in request" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName("public-*".nonempty).nel),
            AlreadyResolved(SnapshotName("n".nonempty).nel)
          ),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty), SnapshotName("q".nonempty)),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd".nonempty)))
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty)),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("x_public-asd".nonempty))
        )
      }
      "write request with configured several snapshots and several snapshots in request" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName("public-*".nonempty).nel),
            AlreadyResolved(SnapshotName("n".nonempty).nel)
          ),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty), SnapshotName("q".nonempty))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty), SnapshotName("q".nonempty))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd".nonempty), SnapshotName("q".nonempty)),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                              requestAction: Action,
                              requestSnapshots: Set[SnapshotName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: SnapshotRequestBlockContext => Unit): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                                 requestAction: Action,
                                 requestSnapshots: Set[SnapshotName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                         requestAction: Action,
                         requestSnapshots: Set[SnapshotName],
                         readonlyRequest: Boolean,
                         blockContextAssertion: Option[SnapshotRequestBlockContext => Unit]) = {
    val rule = new SnapshotsRule(SnapshotsRule.Settings(configuredSnapshots))
    val requestContext = MockRequestContext.snapshots.copy(
      snapshots = requestSnapshots,
      action = requestAction,
      isReadOnlyRequest = readonlyRequest
    )
    val blockContext = SnapshotRequestBlockContext(
      requestContext, UserMetadata.empty, Set.empty, requestSnapshots, Set.empty, Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }

}
