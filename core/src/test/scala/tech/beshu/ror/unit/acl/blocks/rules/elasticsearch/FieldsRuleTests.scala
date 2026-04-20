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
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, FilterableRequestBlockContext, GeneralNonIndexRequestBlockContext, HasFieldLevelSecurity}
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.FilterableRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{RequestFieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.domain.UriPath
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.mocks.MockRequestContext.{adminAction, roAction, rwAction}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.acl.blocks.rules.elasticsearch.FieldsRuleTests.{Configuration, Fields}
import tech.beshu.ror.utils.TestsUtils.*
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
            incomingBlockContext = emptyFilterable(
              requestContext = MockRequestContext.filterable().copy(action = roAction),
              requestFieldsUsage = RequestFieldsUsage.NotUsingFields
            ),
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
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.CannotExtractFields
              ),
              expectedStrategy = Strategy.FlsAtLuceneLevelApproach
            )
          }
          "there is used field with wildcard" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1")))
              ),
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
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.NotUsingFields
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1")))
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ESWithLucene,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField")))
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(NonEmptyList.one(UsedField.SpecificField.fromString("notAllowedField")))
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
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.NotUsingFields
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "all used fields in request are allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ES,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.one(UsedField("_field1")))
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.EverythingAllowed
            )
          }
          "some field in request is not allowed" in {
            assertMatchRule(
              config = Configuration(
                flsEngine = FlsEngine.ES,
                fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
              ),
              incomingBlockContext = emptyFilterable(
                requestContext = MockRequestContext.filterable().copy(action = roAction),
                requestFieldsUsage = RequestFieldsUsage.UsingFields(
                  NonEmptyList.of(UsedField("_field1"), UsedField("notAllowedField"))
                )
              ),
              expectedStrategy = Strategy.BasedOnBlockContextOnly.NotAllowedFieldsUsed(
                NonEmptyList.one(UsedField.SpecificField.fromString("notAllowedField"))
              )
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
            incomingBlockContext = emptyFilterable(
              requestContext = MockRequestContext.filterable().copy(action = roAction),
              requestFieldsUsage = RequestFieldsUsage.CannotExtractFields
            )
          )
        }
        "there is used field with wildcard" in {
          assertRejectRule(
            config = Configuration(
              flsEngine = FlsEngine.ES,
              fields = Fields(NonEmptyList.of("_field1", "_field2"), AccessMode.Whitelist)
            ),
            incomingBlockContext = emptyFilterable(
              requestContext = MockRequestContext.filterable().copy(action = roAction),
              requestFieldsUsage = RequestFieldsUsage.UsingFields(NonEmptyList.of(UsedField("_fi*"), UsedField("_field1")))
            )
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
            incomingBlockContext = emptyFilterableMultiBlockContext(
              requestContext = MockRequestContext.filterable().copy(action = roAction),
              requestFieldsUsage = RequestFieldsUsage.CannotExtractFields
            ),
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
          incomingBlockContext = emptyFilterable(
            requestContext = MockRequestContext.filterable().copy(action = rwAction),
            requestFieldsUsage = RequestFieldsUsage.CannotExtractFields
          )
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
          incomingBlockContext = emptyFilterable(
            requestContext = MockRequestContext.filterable().copy(action = adminAction),
            requestFieldsUsage = RequestFieldsUsage.NotUsingFields
          )
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
        val restRequest = mock[RestRequest]
        (() => restRequest.path).expects().returning(UriPath.from("/_search"))
        val requestContext = mock[RequestContext]
        (() => requestContext.restRequest).expects().returning(restRequest)
        (() => requestContext.action).expects().returning(MockRequestContext.roAction).anyNumberOfTimes()

        val incomingBlockContext = GeneralNonIndexRequestBlockContext(
          block = mock[Block],
          requestContext = requestContext,
          blockMetadata = BlockMetadata.empty,
          responseHeaders = Set.empty,
          responseTransformations = List.empty
        )
        inside(rule.check(incomingBlockContext).runSyncStep) {
          case Right(Permitted(outBlockContext)) =>
            outBlockContext should be (incomingBlockContext)
        }
      }
    }
  }

  private def assertMatchRule[B <: BlockContext : BlockContextUpdater : HasFieldLevelSecurity](config: Configuration,
                                                                                               incomingBlockContext: B,
                                                                                               expectedStrategy: Strategy) = {
    import HasFieldLevelSecurity.*
    val rule = createRule(config)

    inside(rule.check(incomingBlockContext).runSyncStep) {
      case Right(Permitted(outBlockContext)) =>
        outBlockContext.fieldLevelSecurity.isDefined should be (true)
        outBlockContext.fieldLevelSecurity.get.strategy should be (expectedStrategy)
    }
  }

  private def assertRejectRule[B <: BlockContext : BlockContextUpdater](config: Configuration,
                                                                        incomingBlockContext: B) = {

    val rule = createRule(config)

    rule.check(incomingBlockContext).runSyncStep should be (Right(Denied(NotAuthorized)))
  }

  private def createRule(configuredFLS: Configuration) = {
    val resolvedFields = configuredFLS.fields.values.map(field => AlreadyResolved(DocumentField(NonEmptyString.unsafeFrom(field)).nel))
    new FieldsRule(FieldsRule.Settings(UniqueNonEmptyList.fromNonEmptyList(resolvedFields), configuredFLS.fields.accessMode, configuredFLS.flsEngine))
  }

  private def emptyFilterable(requestContext: RequestContext,
                              requestFieldsUsage: RequestFieldsUsage) = {
    FilterableRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      filter = None,
      fieldLevelSecurity = None,
      requestFieldsUsage = requestFieldsUsage
    )
  }

  private def emptyFilterableMultiBlockContext(requestContext: RequestContext,
                                               requestFieldsUsage: RequestFieldsUsage) = {
    FilterableMultiRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.empty,
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
  final case class Fields(values: NonEmptyList[String], accessMode: AccessMode)

  final case class Configuration(fields: Fields, flsEngine: FlsEngine)
}
