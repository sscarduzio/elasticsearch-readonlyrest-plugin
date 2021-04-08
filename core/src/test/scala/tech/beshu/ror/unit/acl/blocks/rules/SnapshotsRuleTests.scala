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
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.Inside
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
import eu.timepit.refined.auto._

class SnapshotsRuleTests extends AnyWordSpec with Inside {

  "A SnapshotsRule" should {
    "match" when {
      "allowed snapshots set contains *" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("snapshot1"))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("snapshot1")))
        }
      }
      "allowed snapshots set contains _all" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("_all").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("snapshot1"))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("snapshot1")))
        }
      }
      "readonly request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-asd").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd")))
        }
      }
      "readonly request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd")))
        }
      }
      "write request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-asd").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd")))
        }
      }
      "write request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd")))
        }
      }
      "readonly request with configured several snapshots and several snapshots in request" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName("public-*").nel),
            AlreadyResolved(SnapshotName("n").nel)
          ),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"), SnapshotName("q")),
          readonlyRequest = true
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName("public-asd")))
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd")),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("public-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("x_public-asd"))
        )
      }
      "write request with configured several snapshots and several snapshots in request" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName("public-*").nel),
            AlreadyResolved(SnapshotName("n").nel)
          ),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"), SnapshotName("q"))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"), SnapshotName("q"))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName("x-*").nel)),
          requestAction = Action("cluster:admin/snapshot/resolve"),
          requestSnapshots = Set(SnapshotName("public-asd"), SnapshotName("q")),
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
      requestContext, UserMetadata.empty, Set.empty, List.empty, requestSnapshots, Set.empty, Set.empty, Set.empty
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
