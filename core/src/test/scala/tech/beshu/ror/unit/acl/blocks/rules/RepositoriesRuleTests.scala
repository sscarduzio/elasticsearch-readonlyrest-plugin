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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RepositoryRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.RepositoriesRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.{Action, RepositoryName}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.concurrent.duration._
import scala.language.postfixOps
import eu.timepit.refined.auto._

class RepositoriesRuleTests extends AnyWordSpec with Inside {

  "A RepositoriesRule" should {
    "match" when {
      "request action doesn't contain 'repository'" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("repository1").nel)),
          requestAction = Action("cluster:ror/user_metadata/get"),
          requestRepositories = Set(RepositoryName("repository1"))
        ) {
          _.repositories should be(Set(RepositoryName("repository1")))
        }
      }
      "allowed indexes set contains *" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("repository1"))
        ) {
          _.repositories should be(Set(RepositoryName("repository1")))
        }
      }
      "allowed indexes set contains _all" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("_all").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("repository1"))
        ) {
          _.repositories should be(Set(RepositoryName("repository1")))
        }
      }
      "readonly request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("public-asd").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd")),
          readonlyRequest = true
        ) {
          _.repositories should be(Set(RepositoryName("public-asd")))
        }
      }
      "readonly request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("public-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd")),
          readonlyRequest = true
        ) {
          _.repositories should be(Set(RepositoryName("public-asd")))
        }
      }
      "write request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("public-asd").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"))
        ) {
          _.repositories should be(Set(RepositoryName("public-asd")))
        }
      }
      "write request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("public-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"))
        ) {
          _.repositories should be(Set(RepositoryName("public-asd")))
        }
      }
      "readonly request with configured several repositorys and several repositorys in request" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.of(AlreadyResolved(RepositoryName("public-*").nel), AlreadyResolved(RepositoryName("n").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"), RepositoryName("q")),
          readonlyRequest = true
        ) {
          blockContext => blockContext.repositories should be(Set(RepositoryName("public-asd")))
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("x-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd")),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("public-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("x_public-asd"))
        )
      }
      "write request with configured several repositories and several repositories in request" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.of(
            AlreadyResolved(RepositoryName("public-*").nel),
            AlreadyResolved(RepositoryName("n").nel)
          ),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"), RepositoryName("q"))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("x-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"), RepositoryName("q"))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(RepositoryName("x-*").nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(RepositoryName("public-asd"), RepositoryName("q")),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]],
                              requestAction: Action,
                              requestRepositories: Set[RepositoryName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: RepositoryRequestBlockContext => Unit): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]],
                                 requestAction: Action,
                                 requestRepositories: Set[RepositoryName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]],
                         requestAction: Action,
                         requestRepositories: Set[RepositoryName],
                         readonlyRequest: Boolean,
                         blockContextAssertion: Option[RepositoryRequestBlockContext => Unit]) = {
    val rule = new RepositoriesRule(RepositoriesRule.Settings(configuredRepositories))
    val requestContext = MockRequestContext.repositories.copy(
      repositories = requestRepositories,
      action = requestAction,
      isReadOnlyRequest = readonlyRequest
    )
    val blockContext = RepositoryRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, requestRepositories)
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
