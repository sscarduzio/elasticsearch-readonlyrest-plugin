package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.WordSpec
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.ops.header.order

class HeadersAndRuleTests extends WordSpec with MockFactory {

  "An HeadersAndRule" should {
    "match" when {
      "one header is configured and one header is passed with request" when {
        "there is no other additional header in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue")),
            requestHeaders = Set(Header.create("hkey" -> "hvalue"))
          )
        }
        "value of the header contains ':'" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue:pass")),
            requestHeaders = Set(Header.create("hkey" -> "hvalue:pass"))
          )
        }
        "contigured header has capitals its name" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("Hkey" -> "hvalue")),
            requestHeaders = Set(Header.create("hkey" -> "hvalue"))
          )
        }
        "request header has capitals its name" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue")),
            requestHeaders = Set(Header.create("Hkey" -> "hvalue"))
          )
        }
        "there are additional headers in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue")),
            requestHeaders = Set(Header.create("Hkey" -> "hvalue"), Header.create("other" -> "header"))
          )
        }
        "configured header value has wildcard character in its value" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "h*")),
            requestHeaders = Set(Header.create("Hkey" -> "hvalue"))
          )
        }
      }

      "two headers are configured and two headers are passed with request" when {
        "their names are different" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey1" -> "hvalue2"), Header.create("hkey2" -> "hvalue2")),
            requestHeaders = Set(Header.create("hkey1" -> "hvalue2"), Header.create("hkey2" -> "hvalue2"))
          )
        }
        "their names are the same" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue2"), Header.create("hkey" -> "hvalue2")),
            requestHeaders = Set(Header.create("hkey" -> "hvalue2"), Header.create("hkey" -> "hvalue2"))
          )
        }
      }

    }
    "not match" when {
      "two headers are configured, but only one is passed" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(Header.create("hkey" -> "hvalue2"), Header.create("hkey" -> "hvalue2")),
          requestHeaders = Set(Header.create("hkey" -> "hvalue2"))
        )
      }
    }
  }

  private def assertMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = false)

  private def assertRule(configuredHeaders: NonEmptySet[Header], requestHeaders: Set[Header], isMatched: Boolean ) = {
    val rule = new HeadersAndRule(HeadersAndRule.Settings(configuredHeaders))
    val context = mock[RequestContext]
    (context.getHeaders _).expects().returning(requestHeaders)
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }
}