/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.acl.blocks.rules.http

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.HostsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver

class HostsRuleTests extends AnyWordSpec with MockFactory {

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
          remoteHost = Address.from("x")
        )
      }
      "configured host net address is different then remote host net address in request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("1.1.1.1/24")),
          remoteHost = Address.from("2.2.2.2")
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("google.com")),
          remoteHost = Address.from("yahoo.com")
        )
      }
      "remote address cannot be extracted from ES request" in {
        assertNotMatchRule(
          configuredHosts = NonEmptySet.of(addressValueFrom("google.com")),
          remoteHost = None
        )
      }
    }
  }

  private def assertMatchRule(configuredHosts: NonEmptySet[RuntimeMultiResolvableVariable[Address]], remoteHost: Address) =
    assertRule(configuredHosts, Some(remoteHost), isMatched = true)

  private def assertNotMatchRule(configuredHosts: NonEmptySet[RuntimeMultiResolvableVariable[Address]], remoteHost: Option[Address]) =
    assertRule(configuredHosts, remoteHost, isMatched = false)

  private def assertRule(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[Address]], address: Option[Address], isMatched: Boolean) = {
    val rule = new HostsRule(
      HostsRule.Settings(configuredValues, acceptXForwardedForHeader = false),
      new Ip4sBasedHostnameResolver
    )
    val requestContext = MockRequestContext.metadata.copy(
      remoteAddress = address,
      headers = Set.empty
    )
    val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def addressValueFrom(value: String): RuntimeMultiResolvableVariable[Address] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom[Address](NonEmptyString.unsafeFrom(value)) {
      AlwaysRightConvertible
        .from(extracted => Address.from(extracted.value).getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value")))
    }
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }
}
