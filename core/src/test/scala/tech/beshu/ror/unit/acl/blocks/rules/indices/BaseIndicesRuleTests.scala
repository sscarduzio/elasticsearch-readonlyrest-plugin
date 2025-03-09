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
package tech.beshu.ror.unit.acl.blocks.rules.indices

import cats.data.NonEmptySet
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, Succeeded}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{SupportedVariablesFunctions, TransformationCompiler}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullRemoteDataStreamWithAliases
import tech.beshu.ror.accesscontrol.domain.Template.{ComponentTemplate, IndexTemplate, LegacyTemplate}
import tech.beshu.ror.accesscontrol.matchers.RandomBasedUniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.mocks.{MockFilterableMultiRequestContext, MockGeneralIndexRequestContext, MockRequestContext, MockRestRequest, MockTemplateRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{clusterIndexName, fullDataStreamName, fullIndexName, fullLocalIndexWithAliases, indexPattern, unsafeNes}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

abstract class BaseIndicesRuleTests extends AnyWordSpec with Matchers {

  protected def assertMatchRuleForIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                               requestIndices: Set[RequestedIndex[ClusterIndexName]],
                                               modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity,
                                               modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity,
                                               filteredRequestedIndices: Set[RequestedIndex[ClusterIndexName]]): Assertion =
    assertRuleForIndexRequest(configured, requestIndices, isMatched = true, modifyRequestContext, modifyBlockContext, filteredRequestedIndices)

  protected def assertNotMatchRuleForIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                  requestIndices: Set[RequestedIndex[ClusterIndexName]],
                                                  modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext = identity,
                                                  modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity): Assertion =
    assertRuleForIndexRequest(configured, requestIndices, isMatched = false, modifyRequestContext, modifyBlockContext, Set.empty)

  private def assertRuleForIndexRequest(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                        requestIndices: Set[RequestedIndex[ClusterIndexName]],
                                        isMatched: Boolean,
                                        modifyRequestContext: MockGeneralIndexRequestContext => MockGeneralIndexRequestContext,
                                        modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext,
                                        filteredRequestedIndices: Set[RequestedIndex[ClusterIndexName]]) = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = modifyRequestContext apply MockRequestContext.indices
      .copy(
        filteredIndices = requestIndices,
        action = Action("indices:data/read/search"),
        allIndicesAndAliases = Set(
          fullLocalIndexWithAliases(fullIndexName("test1")),
          fullLocalIndexWithAliases(fullIndexName("test2")),
          fullLocalIndexWithAliases(fullIndexName("test3")),
          fullLocalIndexWithAliases(fullIndexName("test4")),
          fullLocalIndexWithAliases(fullIndexName("test5"))
        )
      )
    val blockContext = modifyBlockContext apply GeneralIndexRequestBlockContext(
        requestContext = requestContext,
        userMetadata = UserMetadata.from(requestContext),
        responseHeaders = Set.empty,
        responseTransformations = List.empty,
        filteredIndices = requestIndices,
        allAllowedIndices = Set.empty
      )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) {
        Fulfilled(GeneralIndexRequestBlockContext(
          requestContext = requestContext,
          userMetadata = blockContext.userMetadata,
          responseHeaders = Set.empty,
          responseTransformations = List.empty,
          filteredIndices = filteredRequestedIndices,
          allAllowedIndices = configuredValues
            .toNonEmptyList.toList
            .collect { case a: AlreadyResolved[ClusterIndexName] => a }
            .flatMap(_.value.toList)
            .toCovariantSet
        ))
      } else {
        Rejected(Some(Cause.IndexNotFound))
      }
    }
  }

  protected def assertMatchRuleForMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                    indexPacks: List[Indices],
                                                    modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity,
                                                    modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext = identity,
                                                    allowed: List[Indices]): Assertion = {
    assertRuleForMultiForIndexRequest(configured, indexPacks, isMatched = true, modifyRequestContext, modifyBlockContext, allowed)
  }

  protected def assertNotMatchRuleForMultiIndexRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                       indexPacks: List[Indices],
                                                       modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext = identity,
                                                       modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext = identity): Assertion = {
    assertRuleForMultiForIndexRequest(configured, indexPacks, isMatched = false, modifyRequestContext, modifyBlockContext, List.empty)
  }

  private def assertRuleForMultiForIndexRequest(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                indexPacks: List[Indices],
                                                isMatched: Boolean,
                                                modifyRequestContext: MockFilterableMultiRequestContext => MockFilterableMultiRequestContext,
                                                modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext,
                                                allowed: List[Indices]) = {
    val rule = new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = RandomBasedUniqueIdentifierGenerator
    )
    val requestContext = modifyRequestContext apply MockRequestContext.filterableMulti
      .copy(
        restRequest = MockRestRequest(method = Method.POST),
        indexPacks = indexPacks,
        action = Action("indices:data/read/mget"),
        allIndicesAndAliases = Set(
          fullLocalIndexWithAliases(fullIndexName("test1"), Set.empty),
          fullLocalIndexWithAliases(fullIndexName("test2"), Set.empty),
          fullLocalIndexWithAliases(fullIndexName("test3"), Set.empty),
          fullLocalIndexWithAliases(fullIndexName("test4"), Set.empty),
          fullLocalIndexWithAliases(fullIndexName("test5"), Set.empty)
        )
      )
    val blockContext = modifyBlockContext apply FilterableMultiRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      indexPacks = indexPacks,
      filter = None
    )
    rule.check(blockContext).runSyncUnsafe() shouldBe {
      if (isMatched) {
        Fulfilled(FilterableMultiRequestBlockContext(
          requestContext = requestContext,
          userMetadata = blockContext.userMetadata,
          responseHeaders = Set.empty,
          responseTransformations = List.empty,
          indexPacks = allowed,
          filter = None
        ))
      } else {
        Rejected(Some(Cause.IndexNotFound))
      }
    }
  }

  protected def assertMatchRuleForTemplateRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                  requestContext: MockTemplateRequestContext,
                                                  templateOperationAfterProcessing: TemplateOperation,
                                                  allAllowedIndices: Set[ClusterIndexName],
                                                  additionalAssertions: TemplateRequestBlockContext => Assertion = noTransformation): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.toOption.get
    ruleResult should matchPattern {
      case Fulfilled(blockContext@TemplateRequestBlockContext(rc, metadata, headers, Nil, operation, _, allowedIndices))
        if rc == requestContext
          && metadata == requestContext.initialBlockContext.userMetadata
          && headers.isEmpty
          && operation == templateOperationAfterProcessing
          && allowedIndices == allAllowedIndices
          && additionalAssertions(blockContext) == Succeeded =>
    }
  }

  protected def assertNotMatchRuleForTemplateRequest(configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                                                     requestContext: MockTemplateRequestContext,
                                                     specialCause: Option[Cause] = None): Assertion = {
    val rule = createIndicesRule(configured)
    val ruleResult = rule.check(requestContext.initialBlockContext).runSyncStep.toOption.get
    ruleResult shouldBe Rejected(specialCause)
  }

  private def createIndicesRule(configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]]) = {
    new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = (_: Refined[Int, Positive]) => "0000000000"
    )
  }

  protected def indexNameVar(value: NonEmptyString): RuntimeMultiResolvableVariable[ClusterIndexName] = {
    variableCreator
      .createMultiResolvableVariableFrom(value)(AlwaysRightConvertible.from(clusterIndexName))
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

  protected implicit class MockTemplateRequestContextOps(underlying: MockTemplateRequestContext) {
    def addExistingTemplates(template: Template, otherTemplates: Template*): MockTemplateRequestContext = {
      underlying.copy(allTemplates = underlying.allTemplates + template ++ otherTemplates.toSet)
    }
  }

  private def noTransformation(blockContext: TemplateRequestBlockContext) = {
    // we check here if sth else than identity was configured
    val controlTemplates: Set[Template] = Set(
      LegacyTemplate(TemplateName("whatever1"), UniqueNonEmptyList.of(indexPattern("*")), Set(clusterIndexName("alias"))),
      IndexTemplate(TemplateName("whatever2"), UniqueNonEmptyList.of(indexPattern("*")), Set(clusterIndexName("alias"))),
      ComponentTemplate(TemplateName("whatever3"), Set(clusterIndexName("alias"))),
    )
    blockContext.responseTemplateTransformation(controlTemplates) should be(controlTemplates)
  }

  protected def fullRemoteIndexWithAliases(clusterName: String,
                                           fullRemoteIndexName: String,
                                           remoteIndexAliases: String*): FullRemoteIndexWithAliases = {
    def fullIndexNameFrom(value: String) = {
      IndexName.Full.fromString(value) match {
        case Some(name) => name
        case _ => throw new IllegalArgumentException(s"Cannot create full index name from '$value'")
      }
    }

    FullRemoteIndexWithAliases(
      ClusterName.Full.fromString(clusterName).getOrElse(throw new IllegalArgumentException(s"Cannot create cluster name from '$clusterName'")),
      fullIndexNameFrom(fullRemoteIndexName),
      IndexAttribute.Opened,
      remoteIndexAliases.map(fullIndexNameFrom).toCovariantSet
    )
  }

  protected def fullRemoteDataStreamWithAliases(clusterName: String,
                                                fullRemoteDataStreamName: NonEmptyString,
                                                remoteDataStreamAliases: NonEmptyString*): FullRemoteDataStreamWithAliases = {
    FullRemoteDataStreamWithAliases(
      ClusterName.Full.fromString(clusterName).getOrElse(throw new IllegalArgumentException(s"Cannot create cluster name from '$clusterName'")),
      fullDataStreamName(fullRemoteDataStreamName),
      aliasesNames = remoteDataStreamAliases.map(fullDataStreamName).toCovariantSet,
      backingIndices = Set(fullIndexName(NonEmptyString.unsafeFrom(".ds-" + fullRemoteDataStreamName.value)))
    )
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty))
}
