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
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, FilterableRequestBlockContext, GeneralNonIndexRequestBlockContext, HasFieldLevelSecurity}
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.FilterableRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{RequestFieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.domain.UriPath
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.mocks.{MockRequestContext, MockSimpleRequestContext}
import tech.beshu.ror.unit.acl.blocks.rules.FieldsRuleTests.{BlockContextCreator, Configuration, Fields, RequestContextCreator}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRuleTests extends AnyWordSpec with MockFactory with Inside {

  "A FieldsRule" when {
    "filterable request is readonly" should {
      "match and use lucene based strategy" when {
        "'lucene' fls engine is used" in {
          assertMatchRule(
            config = Configuration(
              flsEngine = FlsEngine.Lucene,
              fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
            ),
            requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
            incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.NotUsingFields),
            expectedStrategy = Strategy.FlsAtLuceneLevelApproach
          )
        }
        "'es_with_lucene' fls engine is used and" when {
          "request fields can not be extracted" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.CannotExtractFields),
              expectedStrategy = Strategy.FlsAtLuceneLevelApproach
            )
          }
          "there is used field with wildcard" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1")))),
              expectedStrategy = Strategy.FlsAtLuceneLevelApproach
            )
          }
        }
      }
      "match and not use lucene strategy" when {
        "'es_with_lucene' fls engine is used and" when {
          "request fields are not used" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.NotUsingFields),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1")))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField")))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
            )
          }
        }
        "es' fls engine is used and" when {
          "request fields are not used" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ES,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.NotUsingFields),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ES,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1")))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ES,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
              incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField")))),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(UsedField.SpecificField("notAllowedField")))
            )
          }
        }
      }
    }
    "'es' fls engine' is used" should {
      "not match" when {
        "used fields can not be extracted" in {
          assertRejectRule(
            config = Configuration(
              flsEngine = FlsEngine.ES,
              fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
            ),
            requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
            incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.CannotExtractFields)
          )
        }
        "there is used field with wildcard" in {
          assertRejectRule(
            config = Configuration(
              flsEngine = FlsEngine.ES,
              fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
            ),
            requestContext = MockRequestContext.readOnly[FilterableRequestBlockContext],
            incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1"))))
          )
        }
      }
    }
    "filterable multi request is readonly" should {
      "match and use lucene based strategy" when {
        "request fields can not be extracted" in {
          assertMatchRule(
            config = Configuration(
              flsEngine = FlsEngine.ESWithLucene,
              fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
            ),
            requestContext = MockRequestContext.readOnly[FilterableMultiRequestBlockContext],
            incomingBlockContext = emptyFilterableMultiBlockContext(requestFieldsUsage = RequestFieldsUsage.CannotExtractFields),
            expectedStrategy = Strategy.FlsAtLuceneLevelApproach
          )
        }
      }
    }
    "request is not read only" should {
      "not match" in {
        assertRejectRule(
          config = Configuration(
            flsEngine = FlsEngine.ESWithLucene,
            fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
          ),
          requestContext = MockRequestContext.notReadOnly[FilterableRequestBlockContext],
          incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.CannotExtractFields)
        )
      }
    }
    "request is ROR admin request" should {
      "not match" in {
        assertRejectRule(
          config = Configuration(
            flsEngine = FlsEngine.ESWithLucene,
            fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
          ),
          requestContext = MockRequestContext.readOnlyAdmin[FilterableRequestBlockContext],
          incomingBlockContext = emptyFilterable(requestFieldsUsage = RequestFieldsUsage.NotUsingFields)
        )
      }
    }
    "some other non filterable request is readonly" should {
      "not update block context with fields security strategy" in {
        val rule = createRule(
          Configuration(
            flsEngine = FlsEngine.ESWithLucene,
            fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
          )
        )
        val requestContext = mock[RequestContext]
        val incomingBlockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

        (requestContext.uriPath _).expects().returning(UriPath("/_search"))
        (requestContext.isReadOnlyRequest _).expects().returning(true)
        (requestContext.action _).expects().returning(MockRequestContext.DefaultAction)

        inside(rule.check(incomingBlockContext).runSyncStep) {
          case Right(Fulfilled(outBlockContext)) =>
            outBlockContext shouldBe incomingBlockContext
        }
      }
    }
  }

  private def assertMatchRule[B <: BlockContext : BlockContextUpdater : HasFieldLevelSecurity](config: Configuration,
                                                                                               requestContext: RequestContextCreator[B],
                                                                                               incomingBlockContext: BlockContextCreator[B],
                                                                                               expectedStrategy: Strategy) = {
    import HasFieldLevelSecurity._

    val rule = createRule(config)
    val incomingRequest = requestContext(incomingBlockContext)

    inside(rule.check(incomingRequest.initialBlockContext).runSyncStep) {
      case Right(Fulfilled(outBlockContext)) =>
        outBlockContext.fieldLevelSecurity.isDefined shouldBe true
        outBlockContext.fieldLevelSecurity.get.strategy shouldBe expectedStrategy
    }
  }

  private def assertRejectRule[B <: BlockContext : BlockContextUpdater](config: Configuration,
                                                                        requestContext: RequestContextCreator[B],
                                                                        incomingBlockContext: BlockContextCreator[B]) = {

    val rule = createRule(config)
    val incomingRequest = requestContext(incomingBlockContext)

    rule.check(incomingRequest.initialBlockContext).runSyncStep shouldBe Right(Rejected())
  }

  private def createRule(configuredFLS: Configuration) = {
    val resolvedFields = configuredFLS.fields.values.map(field => AlreadyResolved(DocumentField(NonEmptyString.unsafeFrom(field)).nel))
    new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), configuredFLS.fields.accessMode, configuredFLS.flsEngine))
  }

  private def emptyFilterable(requestFieldsUsage: RequestFieldsUsage)
                             (requestContext: RequestContext) = {
    FilterableRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      filter = None,
      fieldLevelSecurity = None,
      requestFieldsUsage = requestFieldsUsage
    )
  }

  private def emptyFilterableMultiBlockContext(requestFieldsUsage: RequestFieldsUsage)
                                              (requestContext: RequestContext) = {
    FilterableMultiRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      indexPacks = List.empty,
      filter = None,
      fieldLevelSecurity = None,
      requestFieldsUsage = requestFieldsUsage
    )
  }
}

object FieldsRuleTests {
  type BlockContextCreator[B <: BlockContext] = RequestContext => B
  type RequestContextCreator[B <: BlockContext] = BlockContextCreator[B] => MockSimpleRequestContext[B]

  final case class Fields(values: NonEmptyList[String], accessMode: AccessMode)

  final case class Configuration(fields: Fields, flsEngine: FlsEngine)
}
