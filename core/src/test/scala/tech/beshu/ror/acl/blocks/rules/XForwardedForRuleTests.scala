package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.{Address, Header}
import tech.beshu.ror.commons.domain.{IPMask, Value}
import tech.beshu.ror.commons.orders._

class XForwardedForRuleTests extends WordSpec with MockFactory {

  "A XForwardedForRule" should {
    "match" when {
      "configured IP is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedIps(NonEmptySet.of(IPMask.getIPMask("1.1.1.1"))),
          xForwardedForHeaderValue = "1.1.1.1"
        )
      }
      "configured net address is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedIps(NonEmptySet.of(IPMask.getIPMask("1.1.1.1/16"))),
          xForwardedForHeaderValue = "1.1.1.2"
        )
      }
      "configured domain address is the same as the one passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(Value.fromString("google.com", Address.apply))),
          xForwardedForHeaderValue = "google.com"
        )
      }
      "localhost is configured and X-Forwarded-For is also localhost" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedIps(NonEmptySet.of(IPMask.getIPMask("127.0.0.1"))),
          xForwardedForHeaderValue = "localhost"
        )
      }
    }
    "not matched" when {
      "configured IP is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedIps(NonEmptySet.of(IPMask.getIPMask("1.1.1.1"))),
          xForwardedForHeaderValue = "1.1.1.2"
        )
      }
      "configured net address is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(Value.fromString("1.1.1.1/16", Address.apply))),
          xForwardedForHeaderValue = "1.1.1.4"
        )
      }
      "configured domain address different than the one passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(Value.fromString("google.com", Address.apply))),
          xForwardedForHeaderValue = "yahoo.com"
        )
      }
      "X-Forwarded-For header is empty" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(Value.fromString("google.com", Address.apply))),
          xForwardedForHeaderValue = ""
        )
      }
    }
  }

  private def assertMatchRule(settings: XForwardedForRule.Settings, xForwardedForHeaderValue: String) =
    assertRule(settings, xForwardedForHeaderValue, isMatched = true)

  private def assertNotMatchRule(settings: XForwardedForRule.Settings, xForwardedForHeaderValue: String) =
    assertRule(settings, xForwardedForHeaderValue, isMatched = false)

  private def assertRule(settings: XForwardedForRule.Settings, xForwardedForHeaderValue: String, isMatched: Boolean) = {
    val rule = new XForwardedForRule(settings)
    val context = mock[RequestContext]
    (context.headers _).expects().returning(Set(Header("X-Forwarded-For" -> xForwardedForHeaderValue)))
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }
}
