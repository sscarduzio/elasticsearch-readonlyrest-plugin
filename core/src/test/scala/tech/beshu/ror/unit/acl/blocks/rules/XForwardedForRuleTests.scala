package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.IPMask
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext

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
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(addressValueFrom("google.com"))),
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
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(addressValueFrom("1.1.1.1/16"))),
          xForwardedForHeaderValue = "1.1.1.4"
        )
      }
      "configured domain address different than the one passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = "yahoo.com"
        )
      }
      "X-Forwarded-For header is empty" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings.createFromAllowedAddresses(NonEmptySet.of(addressValueFrom("google.com"))),
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
    val requestContext = MockRequestContext(headers = Set(headerFrom("X-Forwarded-For" -> xForwardedForHeaderValue)))
    val blockContext = mock[BlockContext]
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }

  private def addressValueFrom(value: String): Value[Address] = {
    Value
      .fromString(value, rv => Right(Address(rv.value)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }
}
