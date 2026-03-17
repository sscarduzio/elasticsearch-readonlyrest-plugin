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
package tech.beshu.ror.mocks

import cats.data.NonEmptyList
import monix.eval.Task
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.Accessible
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest, UserMetadataRequestContext}
import tech.beshu.ror.es.services.{ApiKeyService, EsClusterService, ServiceAccountTokenService}
import tech.beshu.ror.es.services.EsClusterService.{Document, DocumentsAccessibility, IndexOrAlias, IndexUuid}
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.mocks.MockEsServices.MockEsClusterService
import tech.beshu.ror.mocks.MockRequestContext.roAction
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import java.time.{Clock, Instant}

object MockRequestContext {

  val roAction: Action = Action("indices:admin/get")
  val rwAction: Action = Action("indices:data/write/index")
  val adminAction: Action = Action("cluster:internal_ror/user_metadata/get")

  def indices(implicit clock: Clock = Clock.systemUTC()): MockGeneralIndexRequestContext =
    MockGeneralIndexRequestContext(timestamp = clock.instant(), filteredIndices = Set.empty, allAllowedIndices = Set.empty)

  def filterableMulti(implicit clock: Clock = Clock.systemUTC()): MockFilterableMultiRequestContext =
    MockFilterableMultiRequestContext(timestamp = clock.instant(), indexPacks = List.empty, filter = None, fieldLevelSecurity = None, requestFieldsUsage = RequestFieldsUsage.CannotExtractFields)

  def nonIndices(implicit clock: Clock = Clock.systemUTC()): MockGeneralNonIndexRequestContext =
    MockGeneralNonIndexRequestContext(timestamp = clock.instant())

  def filterable(implicit clock: Clock = Clock.systemUTC()): MockFilterableRequestBlockContext =
    MockFilterableRequestBlockContext(timestamp = clock.instant(), indices = Set.empty, allAllowedIndices = Set.empty)

  def repositories(implicit clock: Clock = Clock.systemUTC()): MockRepositoriesRequestContext =
    MockRepositoriesRequestContext(timestamp = clock.instant(), repositories = Set.empty)

  def snapshots(implicit clock: Clock = Clock.systemUTC()): MockSnapshotsRequestContext =
    MockSnapshotsRequestContext(timestamp = clock.instant(), snapshots = Set.empty)

  def dataStreams(implicit clock: Clock = Clock.systemUTC()): MockDataStreamsRequestContext =
    MockDataStreamsRequestContext(timestamp = clock.instant(), dataStreams = Set.empty)

  def metadata(implicit clock: Clock = Clock.systemUTC()): MockUserMetadataRequestContext =
    MockUserMetadataRequestContext(timestamp = clock.instant())

  def template(operation: TemplateOperation, templates: Template*)
              (implicit clock: Clock = Clock.systemUTC()): MockTemplateRequestContext =
    MockTemplateRequestContext(
      clock.instant(),
      templateOperation = operation,
      esServices = MockEsServices.`with`(MockEsClusterService(
        legacyTemplates = templates.toCovariantSet.collect { case t: Template.LegacyTemplate => t },
        indexTemplates = templates.toCovariantSet.collect { case t: Template.IndexTemplate => t },
        componentTemplates = templates.toCovariantSet.collect { case t: Template.ComponentTemplate => t },
      ))
    )

}

final case class MockGeneralIndexRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_search")),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = roAction,
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val esServices: EsServices = MockEsServices.dummy,
                                                filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                                                allAllowedIndices: Set[ClusterIndexName])
  extends RequestContext {

  override type BLOCK_CONTEXT = GeneralIndexRequestBlockContext

  override def initialBlockContext(block: Block): GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty, filteredIndices, allAllowedIndices, Set.empty
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = Some(filteredIndices)

  def withHeaders(header: Header, headers: Header*): MockGeneralIndexRequestContext = {
    withHeaders(header :: headers.toList)
  }

  def withHeaders(headers: Iterable[Header]): MockGeneralIndexRequestContext = {
    this.copy(restRequest = this.restRequest.copy(allHeaders = headers.toCovariantSet))
  }

  def withEsServices(esServices: EsServices): MockGeneralIndexRequestContext = {
    this.copy(esServices = esServices)
  }

}

final case class MockFilterableMultiRequestContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                   override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_msearch")),
                                                   override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = roAction,
                                                   override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isAllowedForDLS: Boolean = false,
                                                   override val esServices: EsServices = MockEsServices.dummy,
                                                   indexPacks: List[Indices],
                                                   filter: Option[Filter],
                                                   fieldLevelSecurity: Option[FieldLevelSecurity],
                                                   requestFieldsUsage: RequestFieldsUsage)
  extends RequestContext {

  override type BLOCK_CONTEXT = FilterableMultiRequestBlockContext

  override def initialBlockContext(block: Block): FilterableMultiRequestBlockContext =
    FilterableMultiRequestBlockContext(
      block, this, BlockMetadata.from(this), Set.empty, List.empty, indexPacks, filter, fieldLevelSecurity, requestFieldsUsage
    )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockGeneralNonIndexRequestContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                   override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_cat/nodes")),
                                                   override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = roAction,
                                                   override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isAllowedForDLS: Boolean = true,
                                                   override val esServices: EsServices = MockEsServices.dummy)
  extends RequestContext {

  override type BLOCK_CONTEXT = GeneralNonIndexRequestBlockContext

  override def initialBlockContext(block: Block): GeneralNonIndexRequestBlockContext =
    GeneralNonIndexRequestBlockContext(block, this, BlockMetadata.from(this), Set.empty, List.empty)

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockFilterableRequestBlockContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                   override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_search")),
                                                   override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = roAction,
                                                   override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isAllowedForDLS: Boolean = true,
                                                   override val esServices: EsServices = MockEsServices.dummy,
                                                   indices: Set[RequestedIndex[ClusterIndexName]],
                                                   allAllowedIndices: Set[ClusterIndexName])
  extends RequestContext {

  override type BLOCK_CONTEXT = FilterableRequestBlockContext

  override def initialBlockContext(block: Block): FilterableRequestBlockContext = FilterableRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty, indices, allAllowedIndices, None
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None

  def withHeaders(header: Header, headers: Header*): MockFilterableRequestBlockContext = {
    withHeaders(header :: headers.toList)
  }

  def withHeaders(headers: Iterable[Header]): MockFilterableRequestBlockContext = {
    this.copy(restRequest = this.restRequest.copy(allHeaders = headers.toCovariantSet))
  }
}

final case class MockRepositoriesRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_snapshot")),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action.RorAction.RorUserMetadataAction,
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val esServices: EsServices = MockEsServices.dummy,
                                                repositories: Set[RepositoryName])
  extends RequestContext {

  override type BLOCK_CONTEXT = RepositoryRequestBlockContext

  override def initialBlockContext(block: Block): RepositoryRequestBlockContext = RepositoryRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty, repositories
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockSnapshotsRequestContext(override val timestamp: Instant,
                                             override val taskId: Long = 0L,
                                             override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                             override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_snapshot/_status")),
                                             override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                             override val `type`: Type = Type("default-type"),
                                             override val action: Action = roAction,
                                             override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                             override val isCompositeRequest: Boolean = false,
                                             override val isAllowedForDLS: Boolean = true,
                                             override val esServices: EsServices = MockEsServices.dummy,
                                             snapshots: Set[SnapshotName])
  extends RequestContext {

  override type BLOCK_CONTEXT = SnapshotRequestBlockContext

  override def initialBlockContext(block: Block): SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty, snapshots, Set.empty, Set.empty, Set.empty
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockDataStreamsRequestContext(override val timestamp: Instant,
                                               override val taskId: Long = 0L,
                                               override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                               override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("_search")),
                                               override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                               override val `type`: Type = Type("default-type"),
                                               override val action: Action = roAction,
                                               override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                               override val isCompositeRequest: Boolean = false,
                                               override val isAllowedForDLS: Boolean = true,
                                               override val esServices: EsServices = MockEsServices.dummy,
                                               dataStreams: Set[DataStreamName])
  extends RequestContext {

  override type BLOCK_CONTEXT = DataStreamRequestBlockContext

  override def initialBlockContext(block: Block): DataStreamRequestBlockContext = DataStreamRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty, dataStreams, BackingIndices.IndicesNotInvolved
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockUserMetadataRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.currentUserMetadataPath),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action.RorAction.RorUserMetadataAction,
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val apiVersion: UserMetadataApiVersion = UserMetadataApiVersion.V1,
                                                override val esServices: EsServices = MockEsServices.dummy)
  extends UserMetadataRequestContext {

  override type BLOCK_CONTEXT = UserMetadataRequestBlockContext

  override def initialBlockContext(block: Block): UserMetadataRequestBlockContext = UserMetadataRequestBlockContext(
    block, this, BlockMetadata.from(this), Set.empty, List.empty
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None

  def withHeaders(header: Header, headers: Header*): MockUserMetadataRequestContext = {
    withHeaders(header :: headers.toList)
  }

  def withHeaders(headers: Iterable[Header]): MockUserMetadataRequestContext = {
    this.copy(restRequest = this.restRequest.copy(allHeaders = headers.toCovariantSet))
  }
}

final case class MockTemplateRequestContext(override val timestamp: Instant,
                                            override val taskId: Long = 0L,
                                            override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                            override val restRequest: MockRestRequest = MockRestRequest(path = UriPath.from("/_index_template")),
                                            override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                            override val `type`: Type = Type("default-type"),
                                            override val action: Action = Action("default-action"),
                                            override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                            override val isCompositeRequest: Boolean = false,
                                            override val isAllowedForDLS: Boolean = true,
                                            override val esServices: EsServices = MockEsServices.dummy,
                                            templateOperation: TemplateOperation)
  extends RequestContext {

  override type BLOCK_CONTEXT = TemplateRequestBlockContext

  override def initialBlockContext(block: Block): TemplateRequestBlockContext = TemplateRequestBlockContext(
    block, this, BlockMetadata.empty, Set.empty, List.empty, templateOperation, identity, Set.empty
  )

  override def requestedIndices: Option[Set[RequestedIndex[ClusterIndexName]]] = None
}

final case class MockRestRequest(override val method: Method = Method.GET,
                                 override val path: UriPath = UriPath.from("_search"),
                                 override val allHeaders: Set[Header] = Set.empty,
                                 override val localAddress: Address = Address.from("localhost").get,
                                 override val remoteAddress: Option[Address] = Address.from("localhost"),
                                 override val content: String = "",
                                 override val contentLength: Information = Bytes(0))
  extends RestRequest

object MockEsServices {

  val dummy = new EsServices(
    clusterService = new MockEsClusterService(),
    serviceAccountTokenService = new MockServiceAccountTokenService(false),
    apiKeyService = new MockApiKeyService(false)
  )

  def `with`(esClusterService: MockEsClusterService): EsServices =
    new EsServices(esClusterService, dummy.serviceAccountTokenService, dummy.apiKeyService)

  def `with`(serviceAccountTokenService: ServiceAccountTokenService): EsServices =
    new EsServices(dummy.clusterService, serviceAccountTokenService, dummy.apiKeyService)

  def `with`(apiKeyService: ApiKeyService): EsServices =
    new EsServices(dummy.clusterService, dummy.serviceAccountTokenService, apiKeyService)

  object MockEsClusterService {
    def apply(allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
              allRemoteIndicesAndAliases: Set[FullRemoteIndexWithAliases] = Set.empty,
              allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
              allRemoteDataStreamsAndAliases: Set[FullRemoteDataStreamWithAliases] = Set.empty,
              legacyTemplates: Set[Template.LegacyTemplate] = Set.empty,
              indexTemplates: Set[Template.IndexTemplate] = Set.empty,
              componentTemplates: Set[Template.ComponentTemplate] = Set.empty,
              allRemoteClusterNames: Set[ClusterName.Full] = Set.empty): MockEsClusterService =
      new MockEsClusterService(
        allIndicesAndAliases = allIndicesAndAliases,
        allRemoteIndicesAndAliases = allRemoteIndicesAndAliases,
        allDataStreamsAndAliases = allDataStreamsAndAliases,
        allRemoteDataStreamsAndAliases = allRemoteDataStreamsAndAliases,
        legacyTemplates = legacyTemplates,
        indexTemplates = indexTemplates,
        componentTemplates = componentTemplates,
        allRemoteClusterNames = allRemoteClusterNames
      )
  }

  class MockEsClusterService(private val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                             private val allRemoteIndicesAndAliases: Set[FullRemoteIndexWithAliases] = Set.empty,
                             private val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                             private val allRemoteDataStreamsAndAliases: Set[FullRemoteDataStreamWithAliases] = Set.empty,
                             private val legacyTemplates: Set[Template.LegacyTemplate] = Set.empty,
                             private val indexTemplates: Set[Template.IndexTemplate] = Set.empty,
                             private val componentTemplates: Set[Template.ComponentTemplate] = Set.empty,
                             private val allRemoteClusterNames: Set[ClusterName.Full] = Set.empty)
    extends EsClusterService {

    override def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases] = allIndicesAndAliases

    override def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases] = allDataStreamsAndAliases

    override def legacyTemplates(implicit id: RequestId): Set[Template.LegacyTemplate] = legacyTemplates

    override def indexTemplates(implicit id: RequestId): Set[Template.IndexTemplate] = indexTemplates

    override def componentTemplates(implicit id: RequestId): Set[Template.ComponentTemplate] = componentTemplates

    override def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full] = allRemoteClusterNames

    override def indexOrAliasUuids(indexOrAlias: IndexOrAlias)(implicit id: RequestId): Set[IndexUuid] = Set.empty

    override def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]] =
      Task.now(allRemoteIndicesAndAliases)

    override def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[DataStreamName.FullRemoteDataStreamWithAliases]] =
      Task.now(allRemoteDataStreamsAndAliases)

    override def allSnapshots(implicit id: RequestId): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] =
      Map.empty

    override def snapshotIndices(repositoryName: RepositoryName.Full, snapshotName: SnapshotName.Full)
                                (implicit id: RequestId): Task[Set[ClusterIndexName]] =
      Task.now(Set.empty)

    override def verifyDocumentAccessibility(document: Document, filter: Filter)
                                            (implicit id: RequestId): Task[DocumentAccessibility] =
      Task.now(Accessible)

    override def verifyDocumentsAccessibility(documents: NonEmptyList[Document], filter: Filter)
                                             (implicit id: RequestId): Task[DocumentsAccessibility] =
      Task.now(Map.empty)
  }

  class MockServiceAccountTokenService(validationResult: Boolean) extends ServiceAccountTokenService {
    override def validateToken(token: AuthorizationToken)
                              (implicit requestId: RequestId): Task[Boolean] = Task.now(validationResult)
  }

  class MockApiKeyService(validationResult: Boolean) extends ApiKeyService {
    override def validateToken(token: AuthorizationToken)
                              (implicit requestId: RequestId): Task[Boolean] = Task.now(validationResult)
  }
}