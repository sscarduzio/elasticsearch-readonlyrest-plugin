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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, FilterableRequestBlockContext, GeneralNonIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRuleTests extends WordSpec with MockFactory with Inside {

  val fieldsAccessMode = AccessMode.Whitelist
  val whitelistedFields = NonEmptyList.of("_field1", "_field2")

  val resolvedFields = whitelistedFields.map(field => AlreadyResolved(DocumentField(field.nonempty).nel))
  val rule = new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), fieldsAccessMode))

  "A FieldsRule" when {
    "filterable request is readonly" should {
      "match and use lucene based strategy with context header" when {
        "fields can not be extracted" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.CantExtractFields)

          assertRuleResult(
            incomingBlockContext = emptyFilterableBlockContext(requestContext),
            expectedContextHeaders = Set(headerFrom("_fields" -> "eyJkb2N1bWVudEZpZWxkcyI6W3sidmFsdWUiOiJfZmllbGQxIn0seyJ2YWx1ZSI6Il9maWVsZDIifV0sIm1vZGUiOnsiJHR5cGUiOiJ0ZWNoLmJlc2h1LnJvci5hY2Nlc3Njb250cm9sLmRvbWFpbi5GaWVsZExldmVsU2VjdXJpdHkuRmllbGRzUmVzdHJpY3Rpb25zLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19")),
            expectedStrategy = Strategy.LuceneContextHeaderApproach
          )
        }
        "there is used field with wildcard" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1"))))

          assertRuleResult(
            incomingBlockContext = emptyFilterableBlockContext(requestContext),
            expectedContextHeaders = Set(headerFrom("_fields" -> "eyJkb2N1bWVudEZpZWxkcyI6W3sidmFsdWUiOiJfZmllbGQxIn0seyJ2YWx1ZSI6Il9maWVsZDIifV0sIm1vZGUiOnsiJHR5cGUiOiJ0ZWNoLmJlc2h1LnJvci5hY2Nlc3Njb250cm9sLmRvbWFpbi5GaWVsZExldmVsU2VjdXJpdHkuRmllbGRzUmVzdHJpY3Rpb25zLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19")),
            expectedStrategy = Strategy.LuceneContextHeaderApproach
          )
        }
      }
      "match and not use context header" when {
        "fields are not used" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.NotUsingFields)

          assertRuleResult(
            incomingBlockContext = emptyFilterableBlockContext(requestContext),
            expectedContextHeaders = Set.empty,
            expectedStrategy = Strategy.BasedOnBlockContextOnly.NothingNotAllowedToModify
          )
        }
        "all used fields in request are allowed" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1"))))

          assertRuleResult(
            incomingBlockContext = emptyFilterableBlockContext(requestContext),
            expectedContextHeaders = Set.empty,
            expectedStrategy = Strategy.BasedOnBlockContextOnly.NothingNotAllowedToModify
          )
        }
        "some field in request is not allowed" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField"))))

          assertRuleResult(
            incomingBlockContext = emptyFilterableBlockContext(requestContext),
            expectedContextHeaders = Set.empty,
            expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsToModify(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
          )
        }
      }
    }
    "filterable multi request is readonly" should {
      "match and use lucene based strategy with context header" when {
        "fields can not be extracted" in {
          val requestContext = mock[RequestContext]

          (requestContext.isReadOnlyRequest _).expects().returning(true)
          (requestContext.fieldsUsage _).expects().returning(FieldsUsage.CantExtractFields)

          assertRuleResultForMulti(
            incomingBlockContext = emptyFilterableMultiBlockContext(requestContext),
            expectedContextHeaders = Set(headerFrom("_fields" -> "eyJkb2N1bWVudEZpZWxkcyI6W3sidmFsdWUiOiJfZmllbGQxIn0seyJ2YWx1ZSI6Il9maWVsZDIifV0sIm1vZGUiOnsiJHR5cGUiOiJ0ZWNoLmJlc2h1LnJvci5hY2Nlc3Njb250cm9sLmRvbWFpbi5GaWVsZExldmVsU2VjdXJpdHkuRmllbGRzUmVzdHJpY3Rpb25zLkFjY2Vzc01vZGUuV2hpdGVsaXN0In19")),
            expectedStrategy = Strategy.LuceneContextHeaderApproach
          )
        }
      }
    }
    "some other non filterable request is readonly" should {
      "not update block context with fields security strategy" in {
        val requestContext = mock[RequestContext]
        val incomingBlockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)

        (requestContext.isReadOnlyRequest _).expects().returning(true)

        inside(rule.check(incomingBlockContext).runSyncStep) {
          case Right(Fulfilled(outBlockContext)) =>
            outBlockContext.contextHeaders shouldBe Set.empty
        }
      }
    }
    "any request is not read only" should {
      "not match" in {
        val requestContext = mock[RequestContext]

        (requestContext.isReadOnlyRequest _).expects().returning(false)

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }

  def assertRuleResult(incomingBlockContext: FilterableRequestBlockContext,
                       expectedContextHeaders: Set[Header],
                       expectedStrategy: Strategy) = {
    inside(rule.check(incomingBlockContext).runSyncStep) {
      case Right(Fulfilled(outBlockContext)) =>
        outBlockContext.contextHeaders shouldBe expectedContextHeaders
        outBlockContext.fieldLevelSecurity.isDefined shouldBe true
        outBlockContext.fieldLevelSecurity.get.strategy shouldBe expectedStrategy
    }
  }

  def assertRuleResultForMulti(incomingBlockContext: FilterableMultiRequestBlockContext,
                               expectedContextHeaders: Set[Header],
                               expectedStrategy: Strategy) = {
    inside(rule.check(incomingBlockContext).runSyncStep) {
      case Right(Fulfilled(outBlockContext)) =>
        outBlockContext.contextHeaders shouldBe expectedContextHeaders
        outBlockContext.fieldLevelSecurity.isDefined shouldBe true
        outBlockContext.fieldLevelSecurity.get.strategy shouldBe expectedStrategy
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

  private def emptyFilterableMultiBlockContext(requestContext: RequestContext) = {
    FilterableMultiRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      contextHeaders = Set.empty,
      indexPacks = List.empty,
      filter = None,
      fieldLevelSecurity = None
    )
  }
}
