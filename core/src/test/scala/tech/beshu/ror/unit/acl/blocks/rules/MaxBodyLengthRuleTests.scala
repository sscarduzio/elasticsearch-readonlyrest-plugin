package tech.beshu.ror.unit.acl.blocks.rules

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import squants.information.{Bytes, Information, Kilobytes}
import tech.beshu.ror.unit.acl.blocks.BlockContext
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.unit.acl.request.RequestContext

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
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) { isMatched })
  }
}
