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

import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, FilterableRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FilterRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.{Filter, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.TestsUtils._

class FilterRuleTests extends WordSpec with MockFactory {

  "A FilterRuleTests" should {
    "match and set filter" when {
      "filter value is const" when {
        "search request block context is used" in {
          val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
          val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
          val requestContext = MockRequestContext.indices.copy(isReadOnlyRequest = false)
          val blockContext = FilterableRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, None)

          rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
            BlockContext.FilterableRequestBlockContext(
              requestContext,
              UserMetadata.empty,
              Set.empty,
              Set.empty,
              Set.empty,
              Some(Filter(NonEmptyString.unsafeFrom("{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}")))
            )
          ))
        }
        "multi search request block context is used" in {
          val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
          val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
          val requestContext = MockRequestContext.indices.copy(isReadOnlyRequest = false)
          val blockContext = FilterableMultiRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, None)

          rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
            BlockContext.FilterableMultiRequestBlockContext(
              requestContext,
              UserMetadata.empty,
              Set.empty,
              List.empty,
              Some(Filter(NonEmptyString.unsafeFrom("{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}")))
            )
          ))
        }
      }
      "filter value can be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.indices.copy(isReadOnlyRequest = false)
        val blockContext = FilterableRequestBlockContext(
          requestContext,
          UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(User.Id("bob".nonempty))),
          Set.empty,
          Set.empty,
          Set.empty,
          None
        )

        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          FilterableRequestBlockContext(
            requestContext,
            UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(User.Id("bob".nonempty))),
            Set.empty,
            Set.empty,
            Set.empty,
            Some(Filter(NonEmptyString.unsafeFrom("{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"bob\"}}}]}}")))
          )
        ))
      }
    }
    "not match" when {
      "filter value cannot be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.indices.copy(isReadOnlyRequest = false)
        val blockContext = FilterableRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, None)

        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
      "request is not allowed for DLS" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.indices.copy(isAllowedForDLS = false)
        val blockContext = FilterableRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, None)

        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
    }
  }

  private def filterValueFrom(value: String): RuntimeSingleResolvableVariable[Filter] = {
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    RuntimeResolvableVariableCreator
      .createSingleResolvableVariableFrom[Filter](value.nonempty)(AlwaysRightConvertible.from(Filter.apply))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Filter Value from $value"))
  }
}
