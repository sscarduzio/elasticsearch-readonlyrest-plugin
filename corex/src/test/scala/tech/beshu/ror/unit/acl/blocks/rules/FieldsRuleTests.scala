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
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.FieldsRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.domain.DocumentField.ADocumentField
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.acl.orders._

class FieldsRuleTests extends WordSpec with MockFactory {

  "A FieldsRule" should {
    "match" when {
      "request is read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1"), ADocumentField("_field2")), None))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (requestContext.isReadOnlyRequest _).expects().returning(true)
        (blockContext.withAddedContextHeader _).expects(headerFrom("_fields" -> "_field1,_field2")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
    }
    "not match" when {
      "request is not read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1")), None))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.isReadOnlyRequest _).expects().returning(false)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Rejected)
      }
    }
  }

}
