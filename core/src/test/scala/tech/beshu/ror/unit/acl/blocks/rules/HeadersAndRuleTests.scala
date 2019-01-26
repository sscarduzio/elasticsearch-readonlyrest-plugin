package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.HeadersAndRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.TestsUtils._

class HeadersAndRuleTests extends WordSpec with MockFactory {

  "A HeadersAndRule" should {
    "match" when {
      "one header is configured and one header is passed with request" when {
        "there is no other additional header in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
          )
        }
        "value of the header contains ':'" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue:pass")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue:pass"))
          )
        }
        "contigured header has capitals its name" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("Hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
          )
        }
        "request header has capitals its name" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("Hkey" -> "hvalue"))
          )
        }
        "there are additional headers in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("Hkey" -> "hvalue"), headerFrom("other" -> "header"))
          )
        }
        "configured header value has wildcard character in its value" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "h*")),
            requestHeaders = Set(headerFrom("Hkey" -> "hvalue"))
          )
        }
      }
      "two headers are configured and two headers are passed with request" when {
        "their names are different" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue2"), headerFrom("hkey2" -> "hvalue2")),
            requestHeaders = Set(headerFrom("hkey1" -> "hvalue2"), headerFrom("hkey2" -> "hvalue2"))
          )
        }
        "their names are the same" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(headerFrom("hkey" -> "hvalue1"), headerFrom("hkey" -> "hvalue2")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue2"), headerFrom("hkey" -> "hvalue1"))
          )
        }
      }
    }
    "not match" when {
      "two headers are configured, but only one is passed" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1"), headerFrom("hkey2" -> "hvalue2")),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "one header is configured, but request contains one, different header" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(headerFrom("hkey2" -> "hvalue2"))
        )
      }
      "one header is configure, but request has no headers" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(headerFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set.empty
        )
      }
    }
  }

  private def assertMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = false)

  private def assertRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header], isMatched: Boolean) = {
    val rule = new HeadersAndRule(HeadersAndRule.Settings(configuredHeaders))
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    (requestContext.headers _).expects().returning(requestHeaders)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }
}