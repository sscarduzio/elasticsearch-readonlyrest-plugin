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

import cats.data.NonEmptyList
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableRequestBlockContext, GeneralNonIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRuleTests extends WordSpec with MockFactory with Inside {

  val fieldsAccessMode = AccessMode.Whitelist
  val whitelistedFields = NonEmptyList.of("_field1", "_field2")

  val resolvedFields = whitelistedFields.map(field => AlreadyResolved(DocumentField(field.nonempty).nel))
  val rule = new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), fieldsAccessMode))

  "A FieldsRule" when {
    "request is readonly" should {
      "match and use lucene based strategy with context header" when {
        "fields can not be extracted" in {
          val requestContext = mock[RequestContext]
          val incomingBlockContext = emptyFilterableBlockContext(requestContext)

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.CantExtractFields)

          inside(rule.check(incomingBlockContext).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.contextHeaders shouldBe Set(headerFrom("_fields" -> "eyJkb2N1bWVudEZpZWxkcyI6W3sidmFsdWUiOiJfZmllbGQxIn0seyJ2YWx1ZSI6Il9maWVsZDIifV0sIm1vZGUiOnsiJHR5cGUiOiJ0ZWNoLmJlc2h1LnJvci5hY2Nlc3Njb250cm9sLmRvbWFpbi5GaWVsZExldmVsU2VjdXJpdHkuRmllbGRzUmVzdHJpY3Rpb25zLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19"))
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe Strategy.LuceneContextHeaderApproach
          }
        }
        "there is a used field with wildcard" in {
          val requestContext = mock[RequestContext]
          val incomingBlockContext = emptyFilterableBlockContext(requestContext)

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1"))))

          inside(rule.check(incomingBlockContext).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.contextHeaders shouldBe Set(headerFrom("_fields" -> "eyJkb2N1bWVudEZpZWxkcyI6W3sidmFsdWUiOiJfZmllbGQxIn0seyJ2YWx1ZSI6Il9maWVsZDIifV0sIm1vZGUiOnsiJHR5cGUiOiJ0ZWNoLmJlc2h1LnJvci5hY2Nlc3Njb250cm9sLmRvbWFpbi5GaWVsZExldmVsU2VjdXJpdHkuRmllbGRzUmVzdHJpY3Rpb25zLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19"))
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe Strategy.LuceneContextHeaderApproach
          }
        }

      }
      "match and not use context header" when {
        "fields are not used" in {
          val requestContext = mock[RequestContext]
          val incomingBlockContext = emptyFilterableBlockContext(requestContext)

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.NotUsingFields)

          inside(rule.check(incomingBlockContext).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.contextHeaders shouldBe Set.empty
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe Strategy.BasedOnBlockContextOnly.NothingNotAllowedToModify
          }
        }
        "all used fields in request are allowed" in {
          val requestContext = mock[RequestContext]
          val incomingBlockContext = emptyFilterableBlockContext(requestContext)

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1"))))

          inside(rule.check(incomingBlockContext).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.contextHeaders shouldBe Set.empty
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe Strategy.BasedOnBlockContextOnly.NothingNotAllowedToModify
          }
        }
        "some field in request is not allowed" in {
          val requestContext = mock[RequestContext]
          val incomingBlockContext = emptyFilterableBlockContext(requestContext)

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField"))))

          inside(rule.check(incomingBlockContext).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.contextHeaders shouldBe Set.empty
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true

              val expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsToModify(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe expectedStrategy
          }
        }
      }
    }
    "request is not read only" should {
      "not match" in {
        val requestContext = mock[RequestContext]

        (requestContext.isReadOnlyRequest _).expects().returning(false)

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }

  private def emptyFilterableBlockContext(requestContext: RequestContext) = {
    FilterableRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      contextHeaders = Set.empty,
      indices = Set.empty,
      filter = None,
      fieldLevelSecurity = None
    )
  }
}
