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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._

class FieldsRuleTests extends WordSpec with MockFactory {

  "A FieldsRule" should {
    "match" when {
      "request is read only" in {
        val rule = new FieldsRule(FieldsRule.Settings(
          NonEmptySet.of(AlreadyResolved(DocumentField.whitelisted("_field1".nonempty).nel), AlreadyResolved(DocumentField.whitelisted("_field2".nonempty).nel))
        ))
        val requestContext = mock[RequestContext]
        (requestContext.isReadOnlyRequest _).expects().returning(true)

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)

        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          GeneralNonIndexRequestBlockContext(
            requestContext,
            UserMetadata.empty,
            Set.empty,
            Set(headerFrom("_fields" -> "W3sidmFsdWUiOiJfZmllbGQxIiwibW9kZSI6eyIkdHlwZSI6InRlY2guYmVzaHUucm9yLmFjY2Vzc2NvbnRyb2wuZG9tYWluLkRvY3VtZW50RmllbGQyLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19LHsidmFsdWUiOiJfZmllbGQyIiwibW9kZSI6eyIkdHlwZSI6InRlY2guYmVzaHUucm9yLmFjY2Vzc2NvbnRyb2wuZG9tYWluLkRvY3VtZW50RmllbGQyLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19XQ=="))
          )
        ))
      }
    }
    "not match" when {
      "request is not read only" in {
        val rule = new FieldsRule(FieldsRule.Settings(NonEmptySet.of(AlreadyResolved(DocumentField.whitelisted("_field1".nonempty).nel))))
        val requestContext = mock[RequestContext]
        (requestContext.isReadOnlyRequest _).expects().returning(false)
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }

}
