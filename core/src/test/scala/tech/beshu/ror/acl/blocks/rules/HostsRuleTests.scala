package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.UnresolvedAddress
import tech.beshu.ror.commons.domain.Value
import tech.beshu.ror.commons.orders._

class HostsRuleTests extends WordSpec with MockFactory {

  "A HostsRule" should {
    "match" when {
      "configured host IP is the same as remote host IP in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("1.1.1.1")
        )
      }
      "configured host net address is the same as remote host net address in request" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1/16", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("1.1.1.2")
        )
      }
      "configured host domain address is the same as remote host domain address in request (different mask)" in {
        assertMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("google.com", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("google.com")
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("cannotresolve.lolol", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("x")
        )
      }
      "configured host net address is different then remote host net address in request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("1.1.1.1/24", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("2.2.2.2")
        )
      }
      "configured host domain adress is different than the one from request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(Value.fromString("google.com", UnresolvedAddress.apply)),
          remoteHost = UnresolvedAddress("yahoo.com")
        )
      }
    }
  }

  private def assertMatchRule(configuredHosts: NonEmptySet[Value[UnresolvedAddress]], remoteHost: UnresolvedAddress) =
    assertRule(configuredHosts, remoteHost, isMatched = true)

  private def assertNotMatchRule(configuredHosts: NonEmptySet[Value[UnresolvedAddress]], remoteHost: UnresolvedAddress) =
    assertRule(configuredHosts, remoteHost, isMatched = false)

  private def assertRule(configuredValues: NonEmptySet[Value[UnresolvedAddress]], address: UnresolvedAddress, isMatched: Boolean) = {
    val rule = new HostsRule(HostsRule.Settings(configuredValues, acceptXForwardedForHeader = false))
    val context = mock[RequestContext]
    (context.getRemoteAddress _).expects().returning(address)
    (context.getHeaders _).expects().returning(Set.empty)
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }
}
