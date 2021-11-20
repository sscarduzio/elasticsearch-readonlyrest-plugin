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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.LocalHostsRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver

class LocalHostsRuleTests extends AnyWordSpec with MockFactory {

  "A LocalHostsRule" should {
    "match" when {
      "configured host IP is the same as local host IP in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("1.1.1.1")),
          localAddress = Address.from("1.1.1.1").get
        )
      }
      "configured host domain address is the same as local host domain address in request" in {
        assertMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("google.com")),
          localAddress = Address.from("google.com").get
        )
      }
    }
    "not match" when {
      "configured host is unresolvable" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("cannotresolve.lolol")),
          localAddress = Address.from("x").get
        )
      }
      "configured host domain address is different than the one from request" in {
        assertNotMatchRule(
          configuredAddresses = NonEmptySet.of(addressValueFrom("google.com")),
          localAddress = Address.from("yahoo.com").get
        )
      }
    }
  }

  private def assertMatchRule(configuredAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]], localAddress: Address) =
    assertRule(configuredAddresses, localAddress, isMatched = true)

  private def assertNotMatchRule(configuredAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]], localAddress: Address) =
    assertRule(configuredAddresses, localAddress, isMatched = false)

  private def assertRule(configuredAddresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]], localAddress: Address, isMatched: Boolean) = {
    val rule = new LocalHostsRule(
      LocalHostsRule.Settings(configuredAddresses),
      new Ip4sBasedHostnameResolver
    )
    val requestContext = MockRequestContext.metadata.copy(localAddress = localAddress)
    val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def addressValueFrom(value: String): RuntimeMultiResolvableVariable[Address] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(NonEmptyString.unsafeFrom(value))(AlwaysRightConvertible.from(str => Address.from(str.value).get))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }
}
