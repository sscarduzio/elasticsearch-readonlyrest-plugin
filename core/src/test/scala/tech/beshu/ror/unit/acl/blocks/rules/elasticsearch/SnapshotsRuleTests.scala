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
package tech.beshu.ror.unit.acl.blocks.rules.elasticsearch

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.SnapshotsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.{Action, LoggedUser, SnapshotName, User}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps

class SnapshotsRuleTests extends AnyWordSpec with Inside with MockFactory {

  "A SnapshotsRule" should {
    "match" when {
      "allowed snapshots set contains *" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.Wildcard.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("snapshot1").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("snapshot1").get))
        }
      }
      "allowed snapshots set contains _all" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.All.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("snapshot1").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("snapshot1").get))
        }
      }
      "readonly request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("public-asd").get.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("public-asd").get))
        }
      }
      "readonly request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("public-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("public-asd").get))
        }
      }
      "write request with configured simple snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("public-asd").get.nel)),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("public-asd").get))
        }
      }
      "write request with configured snapshot with wildcard" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("public-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("public-asd").get))
        }
      }
      "readonly request with configured several snapshots and several snapshots in request" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName.from("public-*").get.nel),
            AlreadyResolved(SnapshotName.from("n").get.nel)
          ),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get, SnapshotName.from("q").get)
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("public-asd").get))
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("x-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get)
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("public-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("x_public-asd").get)
        )
      }
      "write request with configured several snapshots and several snapshots in request" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.of(
            AlreadyResolved(SnapshotName.from("public-*").get.nel),
            AlreadyResolved(SnapshotName.from("n").get.nel)
          ),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get, SnapshotName.from("q").get)
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("x-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get, SnapshotName.from("q").get)
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(AlreadyResolved(SnapshotName.from("x-*").get.nel)),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("public-asd").get, SnapshotName.from("q").get)
        )
      }
    }
    "match a runtime variable" when {
      // Dynamic path: the configured value is a `ToBeResolved` runtime variable (`@{user}`), so the
      // rule resolves it per request via `resolveAll` (the `getOrElse` fallback) rather than the
      // precomputed static matcher. Guards the non-static branch left unchanged by the optimization.
      "it resolves to the requested snapshot" in {
        assertMatchRule(
          configuredSnapshots = NonEmptySet.one(snapshotNameVar("@{user}")),
          requestAction = Action("cluster:admin/snapshot/get"),
          requestSnapshots = Set(SnapshotName.from("user-snap").get),
          loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user-snap")))
        ) {
          blockContext => blockContext.snapshots should be (Set(SnapshotName.from("user-snap").get))
        }
      }
    }
    "not match a runtime variable" when {
      "it resolves to a snapshot different from the requested one" in {
        assertNotMatchRule(
          configuredSnapshots = NonEmptySet.one(snapshotNameVar("@{user}")),
          requestAction = Action("cluster:admin/snapshot/create"),
          requestSnapshots = Set(SnapshotName.from("other-snap").get),
          loggedUser = Some(LoggedUser.DirectlyLoggedUser(User.Id("user-snap")))
        )
      }
    }
  }

  private def assertMatchRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                              requestAction: Action,
                              requestSnapshots: Set[SnapshotName],
                              loggedUser: Option[LoggedUser.DirectlyLoggedUser] = None)
                             (blockContextAssertion: SnapshotRequestBlockContext => Unit): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, loggedUser, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                                 requestAction: Action,
                                 requestSnapshots: Set[SnapshotName],
                                 loggedUser: Option[LoggedUser.DirectlyLoggedUser] = None): Unit =
    assertRule(configuredSnapshots, requestAction, requestSnapshots, loggedUser, blockContextAssertion = None)

  private def assertRule(configuredSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]],
                         requestAction: Action,
                         requestSnapshots: Set[SnapshotName],
                         loggedUser: Option[LoggedUser.DirectlyLoggedUser],
                         blockContextAssertion: Option[SnapshotRequestBlockContext => Unit]) = {
    val rule = new SnapshotsRule(SnapshotsRule.Settings(configuredSnapshots))
    val requestContext = MockRequestContext.snapshots.copy(
      snapshots = requestSnapshots,
      action = requestAction
    )
    val blockMetadata = loggedUser.foldLeft(BlockMetadata.empty)(_.withLoggedUser(_))
    val blockContext = SnapshotRequestBlockContext(
      mock[Block], requestContext, blockMetadata, Set.empty, List.empty, requestSnapshots, Set.empty, Set.empty, Set.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Permitted(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Denied(NotAuthorized))
    }
  }

  private def snapshotNameVar(value: String): RuntimeMultiResolvableVariable[SnapshotName] = {
    implicit val convertible: AlwaysRightConvertible[SnapshotName] =
      AlwaysRightConvertible.from(str => SnapshotName.from(str.value).getOrElse(SnapshotName.All))
    variableCreator
      .createMultiResolvableVariableFrom(NonEmptyString.unsafeFrom(value))
      .getOrElse(throw new IllegalStateException(s"Cannot create SnapshotName variable from $value"))
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))

}
