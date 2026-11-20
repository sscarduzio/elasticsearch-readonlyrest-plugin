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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, Inside, Succeeded}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{
  RuntimeMultiResolvableVariable,
  RuntimeResolvableVariableCreator
}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.{
  SupportedVariablesFunctions,
  TransformationCompiler
}
import tech.beshu.ror.accesscontrol.blocks.{Block, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullRemoteDataStreamWithAliases
import tech.beshu.ror.accesscontrol.domain.Template.{ComponentTemplate, IndexTemplate, LegacyTemplate}
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, RandomBasedUniqueIdentifierGenerator}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.mocks.*
import tech.beshu.ror.mocks.MockEsServices.MockEsClusterService
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.{
  BlockContextAssertion,
  clusterIndexName,
  fullDataStreamName,
  fullIndexName,
  fullLocalDataStreamWithAliases,
  fullLocalIndexWithAliases,
  indexPattern,
  unsafeNes
}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

abstract class BaseIndicesRuleTests extends AnyWordSpec with Matchers with Inside with BlockContextAssertion {

  protected def assertMatchRuleForIndexRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestIndices: Set[RequestedIndex[ClusterIndexName]],
      esServices: Option[EsServices] = None,
      modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity,
      filteredRequestedIndices: Set[RequestedIndex[ClusterIndexName]],
      allAllowedClusters: Set[ClusterName.Full] = Set(ClusterName.Full.local)
  ): Unit =
    assertRuleForIndexRequest(
      configured,
      requestIndices,
      isMatched = true,
      esServices,
      modifyBlockContext,
      filteredRequestedIndices,
      allAllowedClusters
    )

  protected def assertNotMatchRuleForIndexRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestIndices: Set[RequestedIndex[ClusterIndexName]],
      esServices: Option[EsServices] = None,
      modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity,
      allAllowedClusters: Set[ClusterName.Full] = Set(ClusterName.Full.local)
  ): Unit =
    assertRuleForIndexRequest(
      configured,
      requestIndices,
      isMatched = false,
      esServices,
      modifyBlockContext,
      Set.empty,
      allAllowedClusters
    )

  private def assertRuleForIndexRequest(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestIndices: Set[RequestedIndex[ClusterIndexName]],
      isMatched: Boolean,
      esServices: Option[EsServices],
      modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext,
      filteredRequestedIndices: Set[RequestedIndex[ClusterIndexName]],
      allAllowedClusters: Set[ClusterName.Full]
  ): Unit = {
    val result = checkIndexRequest(configuredValues, requestIndices, esServices, modifyBlockContext).runSyncUnsafe()
    if (isMatched) {
      inside(result) { case Permitted(blockContext: GeneralIndexRequestBlockContext) =>
        assertBlockContext(blockContext)(
          loggedUser = blockContext.blockMetadata.loggedUser,
          indices = filteredRequestedIndices,
          kibanaPolicy = blockContext.blockMetadata.kibanaPolicy
        )
      }
    } else {
      result should be(Denied(Cause.IndexNotFound(allAllowedClusters)))
    }
  }

  protected def checkIndexRequest(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestIndices: Set[RequestedIndex[ClusterIndexName]],
      esServices: Option[EsServices],
      modifyBlockContext: GeneralIndexRequestBlockContext => GeneralIndexRequestBlockContext = identity
  ): Task[Decision[GeneralIndexRequestBlockContext]] = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = MockRequestContext.indices
      .copy(
        filteredIndices = requestIndices,
        action = Action("indices:data/read/search"),
        esServices = esServices match {
          case Some(services) => services
          case None           =>
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(
                  fullLocalIndexWithAliases(fullIndexName("test1")),
                  fullLocalIndexWithAliases(fullIndexName("test2")),
                  fullLocalIndexWithAliases(fullIndexName("test3")),
                  fullLocalIndexWithAliases(fullIndexName("test4")),
                  fullLocalIndexWithAliases(fullIndexName("test5"))
                )
              )
            )
        }
      )
    val blockContext = modifyBlockContext apply GeneralIndexRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = requestIndices,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    rule.check(blockContext)
  }

  protected def checkAliasRequest(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      indices: Set[RequestedIndex[ClusterIndexName]],
      aliases: Set[RequestedIndex[ClusterIndexName]],
      esServices: EsServices
  ): Task[Decision[AliasRequestBlockContext]] = {
    val rule = createIndicesRule(configuredValues)
    val requestContext = MockRequestContext.indices.copy(
      action = Action("indices:admin/aliases/get"),
      esServices = esServices
    )
    val blockContext = AliasRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      aliases = aliases,
      indices = indices
    )
    rule.check(blockContext)
  }

  protected def assertMatchRuleForMultiIndexRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      indexPacks: List[Indices],
      esServices: Option[EsServices] = None,
      modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext = identity,
      allowed: List[Indices]
  ): Unit = {
    assertRuleForMultiForIndexRequest(configured, indexPacks, isMatched = true, esServices, modifyBlockContext, allowed)
  }

  protected def assertNotMatchRuleForMultiIndexRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      indexPacks: List[Indices],
      esServices: Option[EsServices] = None,
      modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext = identity
  ): Unit = {
    assertRuleForMultiForIndexRequest(
      configured,
      indexPacks,
      isMatched = false,
      esServices,
      modifyBlockContext,
      List.empty
    )
  }

  private def assertRuleForMultiForIndexRequest(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      indexPacks: List[Indices],
      isMatched: Boolean,
      esServices: Option[EsServices],
      modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext,
      allowed: List[Indices]
  ): Unit = {
    val result = checkMultiIndexRequest(configuredValues, indexPacks, esServices, modifyBlockContext).runSyncUnsafe()
    if (isMatched) {
      inside(result) { case Permitted(blockContext: FilterableMultiRequestBlockContext) =>
        assertBlockContext(blockContext)(
          indexPacks = allowed,
          kibanaPolicy = blockContext.blockMetadata.kibanaPolicy
        )
      }
    } else {
      result should be(Denied(Cause.IndexNotFound(Set(ClusterName.Full.local))))
    }
  }

  protected def checkMultiIndexRequest(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      indexPacks: List[Indices],
      esServices: Option[EsServices],
      modifyBlockContext: FilterableMultiRequestBlockContext => FilterableMultiRequestBlockContext = identity
  ): Task[Decision[FilterableMultiRequestBlockContext]] = {
    val rule = new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices = false),
      identifierGenerator = RandomBasedUniqueIdentifierGenerator
    )
    val requestContext = MockRequestContext.filterableMulti
      .copy(
        restRequest = MockRestRequest(method = Method.POST),
        indexPacks = indexPacks,
        action = Action("indices:data/read/mget"),
        esServices = esServices match {
          case Some(services) => services
          case None           =>
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(
                  fullLocalIndexWithAliases(fullIndexName("test1")),
                  fullLocalIndexWithAliases(fullIndexName("test2")),
                  fullLocalIndexWithAliases(fullIndexName("test3")),
                  fullLocalIndexWithAliases(fullIndexName("test4")),
                  fullLocalIndexWithAliases(fullIndexName("test5"))
                )
              )
            )
        }
      )
    val blockContext = modifyBlockContext apply FilterableMultiRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      indexPacks = indexPacks,
      filter = None
    )
    rule.check(blockContext)
  }

  protected def assertMatchRuleForTemplateRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestContext: MockTemplateRequestContext,
      templateOperationAfterProcessing: TemplateOperation,
      allAllowedIndices: Set[ClusterIndexName],
      additionalAssertions: TemplateRequestBlockContext => Assertion = noTransformation
  ): Assertion = {
    val rule = createIndicesRule(configured)
    val blockContext = requestContext.initialBlockContext(mock[Block])
    val ruleResult = rule.check(blockContext).runSyncStep.toOption.get
    ruleResult should matchPattern {
      case Permitted(
            blockContext @ TemplateRequestBlockContext(_, rc, metadata, headers, Nil, operation, _, allowedIndices)
          )
          if rc == requestContext
            && metadata == blockContext.blockMetadata
            && headers.isEmpty
            && operation == templateOperationAfterProcessing
            && allowedIndices == allAllowedIndices
            && additionalAssertions(blockContext) == Succeeded =>
    }
  }

  protected def assertNotMatchRuleForTemplateRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestContext: MockTemplateRequestContext,
      specialCause: Cause = NotAuthorized
  ): Assertion = {
    val rule = createIndicesRule(configured)
    val blockContext = requestContext.initialBlockContext(mock[Block])
    val ruleResult = rule.check(blockContext).runSyncStep.toOption.get
    ruleResult should be(Denied(specialCause))
  }

  protected def assertMatchRuleForDataStreamRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      action: Action,
      requestDataStreams: Set[DataStreamName],
      mustInvolveIndices: Boolean = false,
      kibanaIndex: Option[KibanaIndexName] = None
  ): Unit = {
    val result = checkDataStreamRequest(configured, action, requestDataStreams, mustInvolveIndices, kibanaIndex)
    inside(result) { case Permitted(blockContext) =>
      blockContext.dataStreams should be(requestDataStreams)
    }
  }

  // A data stream stats request: the rule leaves only the allowed data streams of the cluster in the request
  protected def assertDataStreamStatsRequestNarrowed(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestDataStreams: Set[DataStreamName],
      clusterDataStreams: Set[DataStreamName.Full],
      expectedDataStreams: Set[DataStreamName],
      kibanaIndex: Option[KibanaIndexName] = None
  ): Unit = {
    val result = checkDataStreamStatsRequest(
      configured,
      requestDataStreams,
      clusterDataStreams,
      mustInvolveIndices = false,
      kibanaIndex
    )
    inside(result) { case Permitted(blockContext) =>
      blockContext.dataStreams should be(expectedDataStreams)
    }
  }

  // A data stream stats request: no data stream is allowed, so the request gets a pattern that matches nothing
  protected def assertDataStreamStatsRequestGetsNoDataStream(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestDataStreams: Set[DataStreamName],
      clusterDataStreams: Set[DataStreamName.Full]
  ): Unit = {
    val result =
      checkDataStreamStatsRequest(configured, requestDataStreams, clusterDataStreams, mustInvolveIndices = false, None)
    inside(result) { case Permitted(blockContext) =>
      inside(blockContext.dataStreams.toList) { case (pattern: DataStreamName.Pattern) :: Nil =>
        val patternMatcher = PatternsMatcher.create[DataStreamName](Set(pattern))
        clusterDataStreams.filter(patternMatcher.`match`) should be(empty)
      }
    }
  }

  protected def assertDataStreamStatsRequestRejected(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestDataStreams: Set[DataStreamName],
      clusterDataStreams: Set[DataStreamName.Full],
      mustInvolveIndices: Boolean
  ): Unit = {
    val result =
      checkDataStreamStatsRequest(configured, requestDataStreams, clusterDataStreams, mustInvolveIndices, None)
    result should be(Denied(NotAuthorized))
  }

  // A data stream request with indices: _modify, migrate, get or lifecycle get
  protected def checkDataStreamRequestWithIndices(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      action: Action,
      requestDataStreams: Set[DataStreamName],
      requestIndices: Set[RequestedIndex[ClusterIndexName]],
      clusterDataStreams: Set[DataStreamName.Full]
  ): Decision[DataStreamRequestBlockContext] = {
    val esServices = MockEsServices.`with`(
      MockEsClusterService(
        allIndicesAndAliases =
          requestIndices.map(index => fullLocalIndexWithAliases(fullIndexName(index.name.stringify))),
        allDataStreamsAndAliases = clusterDataStreams.map(fullLocalDataStreamWithAliases)
      )
    )
    checkDataStreamRequest(
      configured,
      action,
      requestDataStreams.orWildcardWhenEmpty,
      mustInvolveIndices = false,
      kibanaIndex = None,
      esServices,
      BackingIndices.IndicesInvolved(requestIndices, Set(ClusterIndexName.Local.wildcard))
    )
  }

  // A data stream create, delete or promote request in a cluster with the given data streams
  protected def checkDataStreamWriteRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      action: Action,
      requestDataStreams: Set[DataStreamName],
      clusterDataStreams: Set[DataStreamName.Full]
  ): Decision[DataStreamRequestBlockContext] = {
    checkDataStreamRequest(
      configured,
      action,
      requestDataStreams,
      mustInvolveIndices = false,
      kibanaIndex = None,
      esServicesWith(clusterDataStreams)
    )
  }

  private def checkDataStreamStatsRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      requestDataStreams: Set[DataStreamName],
      clusterDataStreams: Set[DataStreamName.Full],
      mustInvolveIndices: Boolean,
      kibanaIndex: Option[KibanaIndexName]
  ): Decision[DataStreamRequestBlockContext] = {
    checkDataStreamRequest(
      configured,
      Action("indices:monitor/data_stream/stats"),
      requestDataStreams,
      mustInvolveIndices,
      kibanaIndex,
      esServicesWith(clusterDataStreams)
    )
  }

  private def esServicesWith(clusterDataStreams: Set[DataStreamName.Full]): EsServices =
    MockEsServices.`with`(
      MockEsClusterService(
        allIndicesAndAliases = Set.empty,
        allDataStreamsAndAliases = clusterDataStreams.map(fullLocalDataStreamWithAliases)
      )
    )

  protected def assertNotMatchRuleForDataStreamRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      action: Action,
      requestDataStreams: Set[DataStreamName],
      mustInvolveIndices: Boolean = false
  ): Unit = {
    val result = checkDataStreamRequest(configured, action, requestDataStreams, mustInvolveIndices, kibanaIndex = None)
    result should be(Denied(NotAuthorized))
  }

  // A data stream request without backing indices: a data stream create, delete, promote or stats
  private def checkDataStreamRequest(
      configured: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      action: Action,
      requestDataStreams: Set[DataStreamName],
      mustInvolveIndices: Boolean,
      kibanaIndex: Option[KibanaIndexName],
      esServices: EsServices = MockEsServices.dummy,
      backingIndices: BackingIndices = BackingIndices.IndicesNotInvolved
  ): Decision[DataStreamRequestBlockContext] = {
    val rule = createIndicesRule(configured, mustInvolveIndices)
    val requestContext =
      MockRequestContext.dataStreams.copy(action = action, dataStreams = requestDataStreams, esServices = esServices)
    val blockMetadata = BlockMetadata.from(requestContext)
    val blockContext = DataStreamRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = kibanaIndex.map(blockMetadata.withKibanaIndex).getOrElse(blockMetadata),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      dataStreams = requestDataStreams,
      backingIndices = backingIndices
    )
    rule.check(blockContext).runSyncUnsafe()
  }

  private def createIndicesRule(
      configuredValues: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      mustInvolveIndices: Boolean = false
  ) = {
    new IndicesRule(
      settings = IndicesRule.Settings(configuredValues, mustInvolveIndices),
      identifierGenerator = (_: Refined[Int, Positive]) => "0000000000"
    )
  }

  protected def indexNameVar(value: NonEmptyString): RuntimeMultiResolvableVariable[ClusterIndexName] = {
    implicit val convertible: AlwaysRightConvertible[ClusterIndexName] = AlwaysRightConvertible.from(clusterIndexName)
    variableCreator
      .createMultiResolvableVariableFrom(value)
      .getOrElse(throw new IllegalStateException(s"Cannot create IndexName Value from $value"))
  }

  private def noTransformation(blockContext: TemplateRequestBlockContext) = {
    // we check here if sth else than identity was configured
    val controlTemplates: Set[Template] = Set(
      LegacyTemplate(
        TemplateName("whatever1"),
        UniqueNonEmptyList.of(indexPattern("*")),
        Set(clusterIndexName("alias"))
      ),
      IndexTemplate(
        TemplateName("whatever2"),
        UniqueNonEmptyList.of(indexPattern("*")),
        Set(clusterIndexName("alias"))
      ),
      ComponentTemplate(TemplateName("whatever3"), Set(clusterIndexName("alias"))),
    )
    blockContext.responseTemplateTransformation(controlTemplates) should be(controlTemplates)
  }

  protected def fullRemoteIndexWithAliases(
      clusterName: String,
      fullRemoteIndexName: String,
      remoteIndexAliases: String*
  ): FullRemoteIndexWithAliases = {
    def fullIndexNameFrom(value: String) = {
      IndexName.Full.fromString(value) match {
        case Some(name) => name
        case _          => throw new IllegalArgumentException(s"Cannot create full index name from '$value'")
      }
    }

    new FullRemoteIndexWithAliases(
      ClusterName.Full
        .fromString(clusterName)
        .getOrElse(throw new IllegalArgumentException(s"Cannot create cluster name from '$clusterName'")),
      fullIndexNameFrom(fullRemoteIndexName),
      IndexAttribute.Opened,
      remoteIndexAliases.map(fullIndexNameFrom).toCovariantSet
    )
  }

  protected def fullRemoteDataStreamWithAliases(
      clusterName: String,
      fullRemoteDataStreamName: NonEmptyString,
      remoteDataStreamAliases: NonEmptyString*
  ): FullRemoteDataStreamWithAliases = {
    FullRemoteDataStreamWithAliases(
      ClusterName.Full
        .fromString(clusterName)
        .getOrElse(throw new IllegalArgumentException(s"Cannot create cluster name from '$clusterName'")),
      fullDataStreamName(fullRemoteDataStreamName),
      aliasesNames = remoteDataStreamAliases.map(fullDataStreamName).toCovariantSet,
      backingIndices = Set(fullIndexName(NonEmptyString.unsafeFrom(".ds-" + fullRemoteDataStreamName.value)))
    )
  }

  private val variableCreator: RuntimeResolvableVariableCreator =
    new RuntimeResolvableVariableCreator(
      TransformationCompiler.withAliases(SupportedVariablesFunctions.default, Seq.empty)
    )

}
