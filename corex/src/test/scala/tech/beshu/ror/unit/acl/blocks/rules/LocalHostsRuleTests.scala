package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext


class LocalHostsRuleTests extends WordSpec with MockFactory {

  "A LocalHostsRule" should {
    "match" when {
      "configured host IP is the same as local host IP in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("1.1.1.1")),
          localAddress = Address("1.1.1.1")
        )
      }
      "configured host domain address is the same as local host domain address in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("google.com")),
          localAddress = Address("google.com")
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("cannotresolve.lolol")),
          localAddress = Address("x")
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("google.com")),
          localAddress = Address("yahoo.com")
        )
      }
    }
  }

  private def assertMatchRule(configuredAddresses: NonEmptySet[Value[Address]], localAddress: Address) =
    assertRule(configuredAddresses, localAddress, isMatched = true)

  private def assertNotMatchRule(configuredAddresses: NonEmptySet[Value[Address]], localAddress: Address) =
    assertRule(configuredAddresses, localAddress, isMatched = false)

  private def assertRule(configuredAddresses: NonEmptySet[Value[Address]], localAddress: Address, isMatched: Boolean) = {
    val rule = new LocalHostsRule(LocalHostsRule.Settings(configuredAddresses))
    val blockContext = mock[BlockContext]
    val requestContext = MockRequestContext(localAddress = localAddress)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right{
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
