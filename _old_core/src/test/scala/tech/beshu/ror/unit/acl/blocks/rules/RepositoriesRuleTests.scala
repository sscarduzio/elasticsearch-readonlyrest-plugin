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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.{BaseSpecializedIndicesRule, RepositoriesRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, RequestContextInitiatedBlockContext}
import tech.beshu.ror.accesscontrol.domain.{Action, IndexName}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.BlockContextAssertion

import scala.concurrent.duration._
import scala.language.postfixOps

class RepositoriesRuleTests
  extends WordSpec with Inside with BlockContextAssertion {

  "A RepositoriesRule" should {
    "match" when {
      "request action doesn't contain 'repository'" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("repository1".nonempty).nel)),
          requestAction = Action("cluster:admin/rradmin/refreshsettings"),
          requestRepositories = Set(IndexName("repository1".nonempty))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains *" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("repository1".nonempty))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains _all" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("_all".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("repository1".nonempty))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("public-asd".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty)),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty)),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("public-asd".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured several repositorys and several repositorys in request" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.of(AlreadyResolved(IndexName("public-*".nonempty).nel), AlreadyResolved(IndexName("n".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty), IndexName("q".nonempty)),
          readonlyRequest = true
        ) {
          blockContext =>
            assertBlockContext(
              repositories = Outcome.Exist(Set(IndexName("public-asd".nonempty)))
            )(blockContext)
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty)),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("public-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("x_public-asd".nonempty))
        )
      }
      "write request with configured several repositorys and several repositorys in request" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.of(AlreadyResolved(IndexName("public-*".nonempty).nel), AlreadyResolved(IndexName("n".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty), IndexName("q".nonempty))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty), IndexName("q".nonempty))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(AlreadyResolved(IndexName("x-*".nonempty).nel)),
          requestAction = Action("cluster:admin/repository/resolve"),
          requestRepositories = Set(IndexName("public-asd".nonempty), IndexName("q".nonempty)),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                              requestAction: Action,
                              requestRepositories: Set[IndexName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                 requestAction: Action,
                                 requestRepositories: Set[IndexName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredRepositories: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                         requestAction: Action,
                         requestRepositories: Set[IndexName],
                         readonlyRequest: Boolean,
                         blockContextAssertion: Option[BlockContext => Unit]) = {
    val rule = new RepositoriesRule(BaseSpecializedIndicesRule.Settings(configuredRepositories))
    val requestContext = MockRequestContext(action = requestAction, repositories = requestRepositories, isReadOnlyRequest = readonlyRequest)
    val blockContext = RequestContextInitiatedBlockContext.fromRequestContext(requestContext)
    val result = rule.check(requestContext, blockContext).runSyncUnsafe(1 second)
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
