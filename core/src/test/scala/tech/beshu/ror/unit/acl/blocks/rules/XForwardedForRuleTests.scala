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

import cats.data.{NonEmptyList, NonEmptySet}
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.{HostnameResolver, XForwardedForRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.MockOnce
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.ResolveResult.{ResolvedIps, Unresolvable}
import tech.beshu.ror.mocks.{MockHostnameResolver, MockRequestContext}
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver
import tech.beshu.ror.utils.TestsUtils._

class XForwardedForRuleTests extends WordSpec with MockFactory {

  "A XForwardedForRule" should {
    "match" when {
      "configured IP is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1"))),
          xForwardedForHeaderValue = "1.1.1.1"
        )
      }
      "configured net address is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1/16"))),
          xForwardedForHeaderValue = "1.1.1.2"
        )
      }
      "configured domain address is the same as the one passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = "google.com"
        )
      }
      "localhost is configured and X-Forwarded-For is also localhost" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("127.0.0.1"))),
          xForwardedForHeaderValue = "localhost"
        )
      }
    }
    "not matched" when {
      "configured IP is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1"))),
          xForwardedForHeaderValue = "1.1.1.2"
        )
      }
      "configured net address is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1/16"))),
          xForwardedForHeaderValue = "2.1.1.1"
        )
      }
      "configured domain address different than the one passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = "yahoo.com"
        )
      }
      "X-Forwarded-For header is empty" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = ""
        )
      }
      "cannot resolve hostname" when {
        "x-forwarded-for header contains unresolvable name and config contains the same name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("unresolvable"))),
            xForwardedForHeaderValue = "unresolvable"
          )
        }
        "only x-forwarded-for header contains unresolvable name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
            xForwardedForHeaderValue = "unresolvable"
          )
        }
        "only config contains unresolvable name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("unresolvable"))),
            xForwardedForHeaderValue = "google.com"
          )
        }
      }
      "hostname can be resolved when rule is created but not during request handling" in {
        val mockedResolver = MockHostnameResolver.create(NonEmptyList.of(
          MockOnce("es-pub7", Unresolvable),
          MockOnce("google.com", ResolvedIps("192.168.0.1/24"))
        ))
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("es-pub7"))),
          xForwardedForHeaderValue = "google.com",
          hostnameResolver = mockedResolver
        )
      }
    }
  }

  private def assertMatchRule(settings: XForwardedForRule.Settings,
                              xForwardedForHeaderValue: String,
                              hostnameResolver: HostnameResolver = new Ip4sBasedHostnameResolver) =
    assertRule(settings, xForwardedForHeaderValue, hostnameResolver, isMatched = true)

  private def assertNotMatchRule(settings: XForwardedForRule.Settings,
                                 xForwardedForHeaderValue: String,
                                 hostnameResolver: HostnameResolver = new Ip4sBasedHostnameResolver) =
    assertRule(settings, xForwardedForHeaderValue, hostnameResolver, isMatched = false)

  private def assertRule(settings: XForwardedForRule.Settings,
                         xForwardedForHeaderValue: String,
                         hostnameResolver: HostnameResolver,
                         isMatched: Boolean) = {
    val rule = new XForwardedForRule(settings, hostnameResolver)
    val requestContext = NonEmptyString.unapply(xForwardedForHeaderValue) match {
      case Some(header) => MockRequestContext.metadata.copy(headers = Set(headerFrom("X-Forwarded-For" -> header.value)))
      case None => MockRequestContext.indices
    }
    val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.empty, Set.empty)
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def addressValueFrom(value: String): RuntimeMultiResolvableVariable[Address] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(value.nonempty)(AlwaysRightConvertible.from(extracted => Address.from(extracted.value).get))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }
}
