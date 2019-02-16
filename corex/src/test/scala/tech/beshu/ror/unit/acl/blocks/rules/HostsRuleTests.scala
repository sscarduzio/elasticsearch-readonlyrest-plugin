package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.rules.HostsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.acl.orders._

class HostsRuleTests extends WordSpec with MockFactory {

  "A HostsRule" should {
    "match" when {
      "configured host IP is the same as remote host IP in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("1.1.1.1")),
          remoteHost = Address.from("1.1.1.1").get
        )
      }
      "configured host net address is the same as remote host net address in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("1.1.0.0/16")),
          remoteHost = Address.from("1.1.1.2").get
        )
      }
      "configured host domain address is the same as remote host domain address in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("google.com")),
          remoteHost = Address.from("google.com").get
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("cannotresolve.lolol")),
          remoteHost = Address.from("x").get
        )
      }
      "configured host net address is different then remote host net address in request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("1.1.1.1/24")),
          remoteHost = Address.from("2.2.2.2").get
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("google.com")),
          remoteHost = Address.from("yahoo.com").get
        )
      }
    }
  }

  private def assertMatchRule(configuredHosts: NonEmptySet[Value[Address]], remoteHost: Address) =
    assertRule(configuredHosts, remoteHost, isMatched = true)

  private def assertNotMatchRule(configuredHosts: NonEmptySet[Value[Address]], remoteHost: Address) =
    assertRule(configuredHosts, remoteHost, isMatched = false)

  private def assertRule(configuredValues: NonEmptySet[Value[Address]], address: Address, isMatched: Boolean) = {
    val rule = new HostsRule(HostsRule.Settings(configuredValues, acceptXForwardedForHeader = false))
    val requestContext = MockRequestContext(
      remoteAddress = address,
      headers = Set.empty
    )
    val blockContext = mock[BlockContext]
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }

  private def addressValueFrom(value: String): Value[Address] = {
    Value
      .fromString(value, rv => Right(Address.from(rv.value).getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }
}
