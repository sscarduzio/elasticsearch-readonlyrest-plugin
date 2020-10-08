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
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.FilterableRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.FLSMode
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{RequestFieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRuleTests extends WordSpec with MockFactory with Inside {

  "A FieldsRule" when {
    "filterable request is readonly" should {
      "match and use lucene based strategy with context header" when {
        "legacy fls mode is used" in {
          assertMatchRuleForReadonlyRequest(
            flsMode = FLSMode.Legacy,
            accessMode = AccessMode.Whitelist,
            fields = NonEmptyList.of("_field1", "_field2"),
            requestFieldsUsage = RequestFieldsUsage.NotUsingFields,
            expectedStrategy = Strategy.FlsAtLuceneLevelApproach
          )
        }
        "hybrid fls mode is used and" when {
          "request fields can not be extracted" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Hybrid,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.CannotExtractFields,
              expectedStrategy = Strategy.FlsAtLuceneLevelApproach
            )
          }
          "there is used field with wildcard" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Hybrid,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1"))),
              expectedStrategy = Strategy.FlsAtLuceneLevelApproach
            )
          }
        }
      }
      "match and not use context header" when {
        "hybrid fls mode is used and" when {
          "request fields are not used" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Hybrid,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.NotUsingFields,
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Hybrid,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1"))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Hybrid,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField"))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
            )
          }
        }
        "proxy fls mode is used and" when {
          "request fields are not used" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Proxy,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.NotUsingFields,
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Proxy,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1"))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRuleForReadonlyRequest(
              flsMode = FLSMode.Proxy,
              accessMode = AccessMode.Whitelist,
              fields = NonEmptyList.of("_field1", "_field2"),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField"))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
            )
          }
        }
      }
    }
    "filterable multi request is readonly" should {
      "match and use lucene based strategy with context header" when {
        "request fields can not be extracted" in {
          val rule = createRule(
            accessMode = AccessMode.Whitelist,
            fields = NonEmptyList.of("_field1", "_field2"),
            flsMode = FLSMode.Hybrid
          )
          val requestContext = mock[RequestContext]
          val requestFieldsUsage = RequestFieldsUsage.CannotExtractFields

          (requestContext.isReadOnlyRequest _).expects().returning(true)

          inside(rule.check(emptyFilterableMultiBlockContext(requestContext, requestFieldsUsage)).runSyncStep) {
            case Right(Fulfilled(outBlockContext)) =>
              outBlockContext.fieldLevelSecurity.isDefined shouldBe true
              outBlockContext.fieldLevelSecurity.get.strategy shouldBe Strategy.FlsAtLuceneLevelApproach
          }
        }
      }
    }
    "some other non filterable request is readonly" should {
      "not update block context with fields security strategy" in {
        val rule = createRule(
          accessMode = AccessMode.Whitelist,
          fields = NonEmptyList.of("_field1", "_field2"),
          flsMode = FLSMode.Hybrid
        )
        val requestContext = mock[RequestContext]
        val incomingBlockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty)

        (requestContext.isReadOnlyRequest _).expects().returning(true)

        inside(rule.check(incomingBlockContext).runSyncStep) {
          case Right(Fulfilled(outBlockContext)) =>
            outBlockContext shouldBe incomingBlockContext
        }
      }
    }
    "any request is not read only" should {
      "not match" in {
        val rule = createRule(
          accessMode = AccessMode.Whitelist,
          fields = NonEmptyList.of("_field1", "_field2"),
          flsMode = FLSMode.Hybrid
        )
        val requestContext = mock[RequestContext]

        (requestContext.isReadOnlyRequest _).expects().returning(false)

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
    "proxy fls mode is used and fields can not be extraced" should {
      "not match" in {
        val rule = createRule(
          accessMode = AccessMode.Whitelist,
          fields = NonEmptyList.of("_field1", "_field2"),
          flsMode = FLSMode.Proxy
        )
        val requestContext = mock[RequestContext]
        val requestsFieldsUsage = RequestFieldsUsage.CannotExtractFields

        (requestContext.isReadOnlyRequest _).expects().returning(true)
        (requestContext.id _).expects().returning(RequestContext.Id("1"))

        val blockContext = emptyFilterableBlockContext(requestContext, requestsFieldsUsage)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }

  private def assertMatchRuleForReadonlyRequest(accessMode: AccessMode,
                                                fields: NonEmptyList[String],
                                                flsMode: FLSMode,
                                                requestFieldsUsage: RequestFieldsUsage,
                                                expectedStrategy: Strategy) = {
    val rule = createRule(accessMode, fields, flsMode)
    val requestContext = mock[RequestContext]

    (requestContext.isReadOnlyRequest _).expects().returning(true)

    inside(rule.check(emptyFilterableBlockContext(requestContext, requestFieldsUsage)).runSyncStep) {
      case Right(Fulfilled(outBlockContext)) =>
        outBlockContext.fieldLevelSecurity.isDefined shouldBe true
        outBlockContext.fieldLevelSecurity.get.strategy shouldBe expectedStrategy
    }
  }

  private def createRule(accessMode: AccessMode,
                         fields: NonEmptyList[String],
                         flsMode: FLSMode) = {
    val resolvedFields = fields.map(field => AlreadyResolved(DocumentField(field.nonempty).nel))
    new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), accessMode, flsMode))
  }

  private def emptyFilterableBlockContext(requestContext: RequestContext,
                                          requestFieldsUsage: RequestFieldsUsage) = {
    FilterableRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      indices = Set.empty,
      filter = None,
      fieldLevelSecurity = None,
      requestFieldsUsage = requestFieldsUsage
    )
  }

  private def emptyFilterableMultiBlockContext(requestContext: RequestContext,
                                               requestFieldsUsage: RequestFieldsUsage) = {
    FilterableMultiRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      indexPacks = List.empty,
      filter = None,
      fieldLevelSecurity = None,
      requestFieldsUsage = requestFieldsUsage
    )
  }
}
