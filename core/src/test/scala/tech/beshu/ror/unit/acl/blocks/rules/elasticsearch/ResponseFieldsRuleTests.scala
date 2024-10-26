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
package tech.beshu.ror.unit.acl.blocks.rules.elasticsearch

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ResponseFieldsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, FilteredResponseFields}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseField, ResponseFieldsRestrictions}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class ResponseFieldsRuleTests extends AnyWordSpec {
  "A ResponseFields rule" should {
    "add appropriate response transformation to block context" when {
      "whitelist mode is used" in {
        assertMatchRule(NonEmptyList.of("field1", "field2"), AccessMode.Whitelist)
      }
      "blacklist mode is used" in {
        assertMatchRule(NonEmptyList.of("blacklistedfield1", "blacklistedfield2"), AccessMode.Blacklist)
      }
    }
  }

  private def assertMatchRule(fields: NonEmptyList[String], mode: AccessMode) = {
    val resolvedFields = fields.map(field => AlreadyResolved(ResponseField(NonEmptyString.unsafeFrom(field)).nel))
    val rule = new ResponseFieldsRule(ResponseFieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), mode))
    val requestContext = MockRequestContext.indices
    val blockContext = GeneralIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

    rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
      BlockContext.GeneralIndexRequestBlockContext(
        requestContext,
        UserMetadata.empty,
        Set.empty,
        FilteredResponseFields(ResponseFieldsRestrictions(UniqueNonEmptyList.fromNonEmptyList(resolvedFields.map(_.value.head)), mode)) :: Nil,
        Set.empty,
        Set.empty
      )
    ))
  }
}
