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

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import squants.information.{Bytes, Information, Kilobytes}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.request.RequestContext

class MaxBodyLengthRuleTests extends WordSpec with MockFactory {

  "A MaxBodyLengthRule" should {
    "match" when {
      "body is short enough" in {
        assertMatchRule(
          configuredMaxContentLength = Kilobytes(1),
          body = "xx"
        )
      }
      "body is empty" in {
        assertMatchRule(
          configuredMaxContentLength = Kilobytes(1),
          body = ""
        )
      }
      "body is same size as configured value" in {
        assertMatchRule(
          configuredMaxContentLength = Bytes(5),
          body = "12345"
        )
      }
    }
    "not match" when {
      "body is bigger than configured value" in {
        assertNotMatchRule(
          configuredMaxContentLength = Bytes(5),
          body = "123456"
        )
      }
    }
  }

  private def assertMatchRule(configuredMaxContentLength: Information, body: String) =
    assertRule(configuredMaxContentLength, body, isMatched = true)

  private def assertNotMatchRule(configuredMaxContentLength: Information, body: String) =
    assertRule(configuredMaxContentLength, body, isMatched = false)

  private def assertRule(configuredMaxContentLength: Information, body: String, isMatched: Boolean) = {
    val rule = new MaxBodyLengthRule(MaxBodyLengthRule.Settings(configuredMaxContentLength))
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    (requestContext.contentLength _).expects().returning(Bytes(body.length))
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right{
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}
