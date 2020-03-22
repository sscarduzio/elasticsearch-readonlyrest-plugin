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
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.HeadersOrRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._

class HeaderOrRuleTests extends WordSpec with MockFactory {

  "A HeadersAndRule" should {
    "match" when {
      "one header was configured and the headers was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "two headers was configured and only one was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1"), headerFrom("hkey2" -> "hvalue2")),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "two headers with same name, but different values was configured and one of them was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue1"), headerFrom("hkey" -> "hvalue2")),
          requestHeaders = Set(headerFrom("hkey" -> "hvalue2"))
        )
      }
      "configured header has wildcard in value" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue*")),
          requestHeaders = Set(headerFrom("hkey" -> "hvalue333"), headerFrom("hkey" -> "different"))
        )
      }
    }
    "not match" when {
      "one header was configured and no headers was passed with request" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set.empty
        )
      }
      "one header was configured and it wasn't passed with request headers" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(headerFrom("hkey2" -> "hvalue2"), headerFrom("hkey3" -> "hvalue3"))
        )
      }
    }
  }

  private def assertMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = false)

  private def assertRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header], isMatched: Boolean) = {
    val rule = new HeadersOrRule(HeadersOrRule.Settings(configuredHeaders))
    val requestContext = mock[RequestContext[Operation]]
    val blockContext = mock[BlockContext]
    (requestContext.headers _).expects().returning(requestHeaders)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}
