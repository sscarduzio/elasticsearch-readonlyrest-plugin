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

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.FilterRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.blocks.values.{Variable, VariableParser}
import tech.beshu.ror.acl.domain.{Filter, LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class FilterRuleTests extends WordSpec with MockFactory {

  "A FilterRuleTests" should {
    "match and set transient filter" when {
      "filter value is const" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.withAddedContextHeader _)
          .expects(headerFrom("_filter" -> "rO0ABXNyACR0ZWNoLmJlc2h1LnJvci51dGlscy5GaWx0ZXJUcmFuc2llbnSE82rPUgVsWwIAAUwAB19maWx0ZXJ0ABJMamF2YS9sYW5nL1N0cmluZzt4cHQAN3siYm9vbCI6eyJtdXN0IjpbeyJ0ZXJtIjp7IkNvdW50cnkiOnsidmFsdWUiOiJVSyJ9fX1dfX0="))
          .returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
      "filter value can be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(User.Id("bob"))))
        val newBlockContext = mock[BlockContext]
        (blockContext.withAddedContextHeader _)
          .expects(headerFrom("_filter" -> "rO0ABXNyACR0ZWNoLmJlc2h1LnJvci51dGlscy5GaWx0ZXJUcmFuc2llbnSE82rPUgVsWwIAAUwAB19maWx0ZXJ0ABJMamF2YS9sYW5nL1N0cmluZzt4cHQANXsiYm9vbCI6eyJtdXN0IjpbeyJ0ZXJtIjp7IlVzZXIiOnsidmFsdWUiOiJib2IifX19XX19"))
          .returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
    }
    "not match" when {
      "filter value cannot be resolved" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isReadOnlyRequest = false)
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
      "request is not allowed for DLS" in {
        val rawFilter = "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
        val rule = new FilterRule(FilterRule.Settings(filterValueFrom(rawFilter)))
        val requestContext = MockRequestContext.default.copy(isAllowedForDLS = false)
        val blockContext = mock[BlockContext]
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
    }
  }

  private def filterValueFrom(value: String): Variable[Filter] = {
    VariableParser
      .parse[Filter](value, extracted => Right(Filter(extracted)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Filter Value from $value"))
  }
}
