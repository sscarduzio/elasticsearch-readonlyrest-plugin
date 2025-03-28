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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.HostsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.Ip4sBasedHostnameResolver

import scala.concurrent.duration.*
import scala.language.postfixOps

class HostsRuleTests extends AnyWordSpec {

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
      restRequest = MockRestRequest(allHeaders = Set.empty, remoteAddress = address)
    )
    val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
    rule.check(blockContext).runSyncUnsafe(10 seconds) shouldBe {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def addressValueFrom(value: String): RuntimeMultiResolvableVariable[Address] = {
    variableCreator
      .createMultiResolvableVariableFrom[Address](NonEmptyString.unsafeFrom(value))(
        AlwaysRightConvertible.from(extracted => Address.from(extracted.value)
          .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
        )
      )
      .getOrElse(throw new IllegalStateException(s"Cannot create Address Value from $value"))
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
