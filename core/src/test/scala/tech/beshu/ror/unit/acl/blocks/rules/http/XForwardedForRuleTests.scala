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

import cats.data.{NonEmptyList, NonEmptySet}
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.XForwardedForRule
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.HostnameResolver
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.MockOnce
import tech.beshu.ror.mocks.MockHostnameResolver.Behaviour.ResolveResult.{ResolvedIps, Unresolvable}
import tech.beshu.ror.mocks.{MockHostnameResolver, MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver
import tech.beshu.ror.utils.TestsUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps

class XForwardedForRuleTests extends AnyWordSpec with MockHostnameResolver {

  "A XForwardedForRule" should {
    "match" when {
      "configured IP is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1"))),
          xForwardedForHeaderValue = Some("1.1.1.1")
        )
      }
      "configured net address is the same as the IP passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1/16"))),
          xForwardedForHeaderValue = Some("1.1.1.2")
        )
      }
      "configured domain address is the same as the one passed in X-Forwarded-For header" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = Some("google.com")
        )
      }
      "localhost is configured and X-Forwarded-For is also localhost" in {
        assertMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("127.0.0.1"))),
          xForwardedForHeaderValue = Some("localhost")
        )
      }
    }
    "not matched" when {
      "configured IP is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1"))),
          xForwardedForHeaderValue = Some("1.1.1.2")
        )
      }
      "configured net address is different than the IP passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("1.1.1.1/16"))),
          xForwardedForHeaderValue = Some("2.1.1.1")
        )
      }
      "configured domain address different than the one passed in X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = Some("yahoo.com")
        )
      }
      "there is no X-Forwarded-For header" in {
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
          xForwardedForHeaderValue = None
        )
      }
      "cannot resolve hostname" when {
        "x-forwarded-for header contains unresolvable name and config contains the same name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("unresolvable"))),
            xForwardedForHeaderValue = Some("unresolvable")
          )
        }
        "only x-forwarded-for header contains unresolvable name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("google.com"))),
            xForwardedForHeaderValue = Some("unresolvable")
          )
        }
        "only config contains unresolvable name" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("unresolvable"))),
            xForwardedForHeaderValue = Some("google.com")
          )
        }
        "0.0.0.0/0 is configured and X-Forwarded-For is not present" in {
          assertNotMatchRule(
            settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("0.0.0.0/0"))),
            xForwardedForHeaderValue = None
          )
        }
      }
      "hostname can be resolved when rule is created but not during request handling" in {
        val mockedResolver = create(NonEmptyList.of(
          MockOnce("es-pub7", Unresolvable),
          MockOnce("google.com", ResolvedIps("192.168.0.1/24"))
        ))
        assertNotMatchRule(
          settings = XForwardedForRule.Settings(NonEmptySet.of(addressValueFrom("es-pub7"))),
          xForwardedForHeaderValue = Some("google.com"),
          hostnameResolver = mockedResolver
        )
      }
    }
  }

  private def assertMatchRule(settings: XForwardedForRule.Settings,
                              xForwardedForHeaderValue: Option[String],
                              hostnameResolver: HostnameResolver = new Ip4sBasedHostnameResolver) =
    assertRule(settings, xForwardedForHeaderValue, hostnameResolver, isMatched = true)

  private def assertNotMatchRule(settings: XForwardedForRule.Settings,
                                 xForwardedForHeaderValue: Option[String],
                                 hostnameResolver: HostnameResolver = new Ip4sBasedHostnameResolver) =
    assertRule(settings, xForwardedForHeaderValue, hostnameResolver, isMatched = false)

  private def assertRule(settings: XForwardedForRule.Settings,
                         xForwardedForHeaderValue: Option[String],
                         hostnameResolver: HostnameResolver,
                         isMatched: Boolean) = {
    val rule = new XForwardedForRule(settings, hostnameResolver)
    val requestContext = xForwardedForHeaderValue match {
      case Some(value) => MockRequestContext.indices.copy(
        restRequest = MockRestRequest(allHeaders = Set(headerFrom("X-Forwarded-For" -> value)))
      )
      case None => MockRequestContext.indices
    }
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.check(blockContext).runSyncUnsafe(10 seconds) shouldBe {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def addressValueFrom(value: String): RuntimeMultiResolvableVariable[Address] = {
    variableCreator
      .createMultiResolvableVariableFrom(NonEmptyString.unsafeFrom(value))(
        AlwaysRightConvertible.from(extracted => Address.from(extracted.value).get)
      )
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
