package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.orders._

class HeaderOrRuleTests extends WordSpec with MockFactory {

  "A HeadersAndRule" should {
    "match" when {
      "one header was configured and the headers was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey1" -> "hvalue1")),
          requestHeaders = Set(Header("hkey1" -> "hvalue1"))
        )
      }
      "two headers was configured and only one was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey1" -> "hvalue1"), Header("hkey2" -> "hvalue2")),
          requestHeaders = Set(Header("hkey1" -> "hvalue1"))
        )
      }
      "two headers with same name, but different values was configured and one of them was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey" -> "hvalue1"), Header("hkey" -> "hvalue2")),
          requestHeaders = Set(Header("hkey" -> "hvalue2"))
        )
      }
      "configured header has wildcard in value" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey" -> "hvalue*")),
          requestHeaders = Set(Header("hkey" -> "hvalue333"), Header("hkey" -> "different"))
        )
      }
    }
    "not match" when {
      "one header was configured and no headers was passed with request" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey1" -> "hvalue1")),
          requestHeaders = Set.empty
        )
      }
      "one header was configured and it wasn't passed with request headers" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(Header("hkey1" -> "hvalue1")),
          requestHeaders = Set(Header("hkey2" -> "hvalue2"), Header("hkey3" -> "hvalue3"))
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
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    (requestContext.headers _).expects().returning(requestHeaders)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) { isMatched })
  }
}
