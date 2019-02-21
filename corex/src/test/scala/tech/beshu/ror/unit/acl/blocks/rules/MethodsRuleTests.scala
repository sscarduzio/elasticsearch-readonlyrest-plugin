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
import com.softwaremill.sttp.Method
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.MethodsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.orders._

class MethodsRuleTests extends WordSpec with MockFactory {

  "A MethodsRule" should {
    "match" when {
      "configured method is GET and request method is also GET" in {
        assertMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET),
          requestMethod = Method.GET
        )
      }
      "configured methods are GET, POST, PUT and request method is GET" in {
        assertMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET, Method.POST, Method.PUT),
          requestMethod = Method.GET
        )
      }
    }
    "not match" when {
      "configured methods are GET, POST, PUT but request method is DELETE" in {
        assertNotMatchRule(
          configuredMethods = NonEmptySet.of(Method.GET, Method.POST, Method.PUT),
          requestMethod = Method.DELETE
        )
      }
    }
  }

  private def assertMatchRule(configuredMethods: NonEmptySet[Method], requestMethod: Method) =
    assertRule(configuredMethods, requestMethod, isMatched = true)

  private def assertNotMatchRule(configuredMethods: NonEmptySet[Method], requestMethod: Method) =
    assertRule(configuredMethods, requestMethod, isMatched = false)

  private def assertRule(configuredMethods: NonEmptySet[Method], requestMethod: Method, isMatched: Boolean) = {
    val rule = new MethodsRule(MethodsRule.Settings(configuredMethods))
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    (requestContext.method _).expects().returning(requestMethod)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }
}
