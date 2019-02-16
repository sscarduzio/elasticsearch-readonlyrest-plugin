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
import tech.beshu.ror.TestsUtils.BlockContextAssertion
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.{BaseSpecializedIndicesRule, RepositoriesRule}
import tech.beshu.ror.acl.blocks.{BlockContext, Const, RequestContextInitiatedBlockContext, Value}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext

import scala.concurrent.duration._
import scala.language.postfixOps

class RepositoriesRuleTests
  extends WordSpec with Inside with BlockContextAssertion[BaseSpecializedIndicesRule.Settings] {

  "A RepositoriesRule" should {
    "match" when {
      "request action doesn't contain 'repository'" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("repository1"))),
          requestAction = Action("cluster:admin/rradmin/refreshsettings"),
          requestRepositories = Set(IndexName("repository1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains *" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("repository1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "allowed indexes set contains _all" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("_all"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("repository1"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("public-asd"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd")),
          readonlyRequest = true
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured simple repository" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("public-asd"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "write request with configured repository with wildcard" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"))
        ) {
          blockContext => assertBlockContext()(blockContext)
        }
      }
      "readonly request with configured several repositorys and several repositorys in request" in {
        assertMatchRule(
          configuredRepositories = NonEmptySet.of(Const(IndexName("public-*")), Const(IndexName("n"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"), IndexName("q")),
          readonlyRequest = true
        ) {
          blockContext =>
            assertBlockContext(
              repositories = Set(IndexName("public-asd"))
            )(blockContext)
        }
      }
    }
    "not match" when {
      "request is read only" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd")),
          readonlyRequest = true
        )
      }
      "write request with no match" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("public-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("x_public-asd"))
        )
      }
      "write request with configured several repositorys and several repositorys in request" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.of(Const(IndexName("public-*")), Const(IndexName("n"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"), IndexName("q"))
        )
      }
      "write request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"), IndexName("q"))
        )
      }
      "read request forbid" in {
        assertNotMatchRule(
          configuredRepositories = NonEmptySet.one(Const(IndexName("x-*"))),
          requestAction = Action("cluster:admin/repository/get"),
          requestRepositories = Set(IndexName("public-asd"), IndexName("q")),
          readonlyRequest = true
        )
      }
    }
  }

  private def assertMatchRule(configuredRepositories: NonEmptySet[Value[IndexName]],
                              requestAction: Action,
                              requestRepositories: Set[IndexName],
                              readonlyRequest: Boolean = false)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, Some(blockContextAssertion))

  private def assertNotMatchRule(configuredRepositories: NonEmptySet[Value[IndexName]],
                                 requestAction: Action,
                                 requestRepositories: Set[IndexName],
                                 readonlyRequest: Boolean = false): Unit =
    assertRule(configuredRepositories, requestAction, requestRepositories, readonlyRequest, blockContextAssertion = None)

  private def assertRule(configuredRepositories: NonEmptySet[Value[IndexName]],
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
        result should be(Rejected)
    }
  }

}
