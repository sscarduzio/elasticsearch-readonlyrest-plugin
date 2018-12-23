package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.mocks.MockRequestContext

class HostsRuleTests extends WordSpec with MockFactory {

  "A HostsRule" should {
    "match" when {
      "configured host IP is the same as remote host IP in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1", rv => Address(rv.value))),
          remoteHost = Address("1.1.1.1")
        )
      }
      "configured host net address is the same as remote host net address in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1/16", rv => Address(rv.value))),
          remoteHost = Address("1.1.1.2")
        )
      }
      "configured host domain address is the same as remote host domain address in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("google.com", rv => Address(rv.value))),
          remoteHost = Address("google.com")
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("cannotresolve.lolol", rv => Address(rv.value))),
          remoteHost = Address("x")
        )
      }
      "configured host net address is different then remote host net address in request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1/24", rv => Address(rv.value))),
          remoteHost = Address("2.2.2.2")
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("google.com", rv => Address(rv.value))),
          remoteHost = Address("yahoo.com")
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
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) { isMatched })
  }
}
