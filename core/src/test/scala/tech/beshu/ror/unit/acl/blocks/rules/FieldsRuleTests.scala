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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.AccessMode
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRuleTests extends WordSpec with MockFactory {

  "A FieldsRule" should {
    "match" when {
      "request is read only" in {
        val fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]] = UniqueNonEmptyList.of(AlreadyResolved(DocumentField("_field1".nonempty).nel), AlreadyResolved(DocumentField("_field2".nonempty).nel))
        val rule = new FieldsRule(FieldsRule.Settings(fields, AccessMode.Whitelist))
        val requestContext = mock[RequestContext]
        (requestContext.isReadOnlyRequest _).expects().returning(true)

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)

        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(
          GeneralNonIndexRequestBlockContext(
            requestContext,
            UserMetadata.empty,
            Set.empty,
            Set(headerFrom("_fields" -> "eyJmaWVsZHMiOlt7InZhbHVlIjoiX2ZpZWxkMSJ9LHsidmFsdWUiOiJfZmllbGQyIn1dLCJtb2RlIjp7IiR0eXBlIjoidGVjaC5iZXNodS5yb3IuYWNjZXNzY29udHJvbC5kb21haW4uRG9jdW1lbnRGaWVsZC5BY2Nlc3NNb2RlLldoaXRlbGlzdCJ9fQ=="))
          )
        ))
      }
    }
    "not match" when {
      "request is not read only" in {
        val rule = new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.of(AlreadyResolved(DocumentField("_field1".nonempty).nel)), AccessMode.Whitelist))
        val requestContext = mock[RequestContext]
        (requestContext.isReadOnlyRequest _).expects().returning(false)
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }

}
