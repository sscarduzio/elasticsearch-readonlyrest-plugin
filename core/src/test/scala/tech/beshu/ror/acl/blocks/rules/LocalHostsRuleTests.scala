package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.domain.Value
import tech.beshu.ror.commons.orders._


class LocalHostsRuleTests extends WordSpec with MockFactory {

  "A LocalHostsRule" should {
    "match" when {
      "configured host IP is the same as local host IP in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(Value.fromString("1.1.1.1", Address.apply)),
          localAddress = Address("1.1.1.1")
        )
      }
      "configured host domain address is the same as local host domain address in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(Value.fromString("google.com", Address.apply)),
          localAddress = Address("google.com")
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(Value.fromString("cannotresolve.lolol", Address.apply)),
          localAddress = Address("x")
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(Value.fromString("google.com", Address.apply)),
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
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    (requestContext.localAddress _).expects().returning(localAddress)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) { isMatched })
  }
}
