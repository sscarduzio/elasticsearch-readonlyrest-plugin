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

import java.time.{Clock, Instant}
import tech.beshu.ror.accesscontrol.request.RequestContext.Method
import eu.timepit.refined.auto._
import monix.eval.Task
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext._
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext.roAction
import tech.beshu.ror.utils.TestsUtils.unsafeNes

import scala.annotation.nowarn

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

  def search(implicit clock: Clock = Clock.systemUTC()): MockSearchRequestContext =
    MockSearchRequestContext(timestamp = clock.instant(), indices = Set.empty, allAllowedIndices = Set.empty)

  def repositories(implicit clock: Clock = Clock.systemUTC()): MockRepositoriesRequestContext =
    MockRepositoriesRequestContext(timestamp = clock.instant(), repositories = Set.empty)

  def snapshots(implicit clock: Clock = Clock.systemUTC()): MockSnapshotsRequestContext =
    MockSnapshotsRequestContext(timestamp = clock.instant(), snapshots = Set.empty)

  def dataStreams(implicit clock: Clock = Clock.systemUTC()): MockDataStreamsRequestContext =
    MockDataStreamsRequestContext(timestamp = clock.instant(), dataStreams = Set.empty)

  def metadata(implicit clock: Clock = Clock.systemUTC()): MockUserMetadataRequestContext =
    MockUserMetadataRequestContext(timestamp = clock.instant())

  def template(templateOperation: TemplateOperation)
              (implicit clock: Clock = Clock.systemUTC()): MockTemplateRequestContext =
    MockTemplateRequestContext(clock.instant(), templateOperation = templateOperation)

  def readOnly[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, customAction = roAction)

  def readOnlyAdmin[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, customAction = adminAction)

  def notReadOnly[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, customAction = rwAction)

}

final case class MockGeneralIndexRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = roAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method.GET,
                                                override val uriPath: UriPath = UriPath.from("_search"),
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true,
                                                filteredIndices: Set[ClusterIndexName],
                                                allAllowedIndices: Set[ClusterIndexName])
  extends RequestContext {
  override type BLOCK_CONTEXT = GeneralIndexRequestBlockContext

  override def initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, filteredIndices, allAllowedIndices
  )
}

final case class MockFilterableMultiRequestContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                   override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = roAction,
                                                   override val headers: Set[Header] = Set.empty,
                                                   override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                   override val localAddress: Address = Address.from("localhost").get,
                                                   override val method: Method = Method.GET,
                                                   override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                   override val contentLength: Information = Bytes(0),
                                                   override val content: String = "",
                                                   override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                   override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                   override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                   override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                   override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                   override val allTemplates: Set[Template] = Set.empty,
                                                   override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isAllowedForDLS: Boolean = false,
                                                   indexPacks: List[Indices],
                                                   filter: Option[Filter],
                                                   fieldLevelSecurity: Option[FieldLevelSecurity],
                                                   requestFieldsUsage: RequestFieldsUsage)
  extends RequestContext {
  override type BLOCK_CONTEXT = FilterableMultiRequestBlockContext

  override def initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, indexPacks, filter, fieldLevelSecurity, requestFieldsUsage
  )

}

final case class MockGeneralNonIndexRequestContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                   override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = roAction,
                                                   override val headers: Set[Header] = Set.empty,
                                                   override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                   override val localAddress: Address = Address.from("localhost").get,
                                                   override val method: Method = Method.GET,
                                                   override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                   override val contentLength: Information = Bytes(0),
                                                   override val content: String = "",
                                                   override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                   override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                   override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                   override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                   override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                   override val allTemplates: Set[Template] = Set.empty,
                                                   override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isAllowedForDLS: Boolean = true)
  extends RequestContext {

  override type BLOCK_CONTEXT = GeneralNonIndexRequestBlockContext

  override def initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty
  )
}

final case class MockSearchRequestContext(override val timestamp: Instant,
                                          override val taskId: Long = 0L,
                                          override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                          override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                          override val `type`: Type = Type("default-type"),
                                          override val action: Action = roAction,
                                          override val headers: Set[Header] = Set.empty,
                                          override val remoteAddress: Option[Address] = Address.from("localhost"),
                                          override val localAddress: Address = Address.from("localhost").get,
                                          override val method: Method = Method.GET,
                                          override val uriPath: UriPath = UriPath.from("/_search"),
                                          override val contentLength: Information = Bytes(0),
                                          override val content: String = "",
                                          override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                          override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                          override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                          override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                          override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                          override val allTemplates: Set[Template] = Set.empty,
                                          override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                          override val isCompositeRequest: Boolean = false,
                                          override val isAllowedForDLS: Boolean = true,
                                          indices: Set[ClusterIndexName],
                                          allAllowedIndices: Set[ClusterIndexName])
  extends RequestContext {
  override type BLOCK_CONTEXT = FilterableRequestBlockContext

  override def initialBlockContext: FilterableRequestBlockContext = FilterableRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, indices, allAllowedIndices, None
  )
}

final case class MockRepositoriesRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action.RorAction.RorUserMetadataAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method.GET,
                                                override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true,
                                                repositories: Set[RepositoryName])
  extends RequestContext {
  override type BLOCK_CONTEXT = RepositoryRequestBlockContext

  override def initialBlockContext: RepositoryRequestBlockContext = RepositoryRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, repositories
  )
}

final case class MockSnapshotsRequestContext(override val timestamp: Instant,
                                             override val taskId: Long = 0L,
                                             override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                             override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                             override val `type`: Type = Type("default-type"),
                                             override val action: Action = roAction,
                                             override val headers: Set[Header] = Set.empty,
                                             override val remoteAddress: Option[Address] = Address.from("localhost"),
                                             override val localAddress: Address = Address.from("localhost").get,
                                             override val method: Method = Method.GET,
                                             override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                             override val contentLength: Information = Bytes(0),
                                             override val content: String = "",
                                             override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                             override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                             override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                             override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                             override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                             override val allTemplates: Set[Template] = Set.empty,
                                             override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                             override val isCompositeRequest: Boolean = false,
                                             override val isAllowedForDLS: Boolean = true,
                                             snapshots: Set[SnapshotName])
  extends RequestContext {
  override type BLOCK_CONTEXT = SnapshotRequestBlockContext

  override def initialBlockContext: SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, snapshots, Set.empty, Set.empty, Set.empty
  )
}

final case class MockDataStreamsRequestContext(override val timestamp: Instant,
                                               override val taskId: Long = 0L,
                                               override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                               override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                               override val `type`: Type = Type("default-type"),
                                               override val action: Action = roAction,
                                               override val headers: Set[Header] = Set.empty,
                                               override val remoteAddress: Option[Address] = Address.from("localhost"),
                                               override val localAddress: Address = Address.from("localhost").get,
                                               override val method: Method = Method.GET,
                                               override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                               override val contentLength: Information = Bytes(0),
                                               override val content: String = "",
                                               override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                               override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                               override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                               override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                               override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                               override val allTemplates: Set[Template] = Set.empty,
                                               override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                               override val isCompositeRequest: Boolean = false,
                                               override val isAllowedForDLS: Boolean = true,
                                               dataStreams: Set[DataStreamName])
  extends RequestContext {
  override type BLOCK_CONTEXT = DataStreamRequestBlockContext

  override def initialBlockContext: DataStreamRequestBlockContext = DataStreamRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, dataStreams, BackingIndices.IndicesNotInvolved
  )
}

final case class MockUserMetadataRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = roAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method.GET,
                                                override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isAllowedForDLS: Boolean = true)
  extends RequestContext {
  override type BLOCK_CONTEXT = CurrentUserMetadataRequestBlockContext

  override def initialBlockContext: CurrentUserMetadataRequestBlockContext = CurrentUserMetadataRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty
  )
}

final case class MockTemplateRequestContext(override val timestamp: Instant,
                                            override val taskId: Long = 0L,
                                            override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                            override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                            override val `type`: Type = Type("default-type"),
                                            override val action: Action = Action("default-action"),
                                            override val headers: Set[Header] = Set.empty,
                                            override val remoteAddress: Option[Address] = Address.from("localhost"),
                                            override val localAddress: Address = Address.from("localhost").get,
                                            override val method: Method = Method.GET,
                                            override val uriPath: UriPath = UriPath.from("/_template").get,
                                            override val contentLength: Information = Bytes(0),
                                            override val content: String = "",
                                            override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                            override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                            override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                            override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                            override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                            override val allTemplates: Set[Template] = Set.empty,
                                            override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                            override val isCompositeRequest: Boolean = false,
                                            override val isAllowedForDLS: Boolean = true,
                                            templateOperation: TemplateOperation)
  extends RequestContext {
  override type BLOCK_CONTEXT = TemplateRequestBlockContext

  override def initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this, UserMetadata.empty, Set.empty, List.empty, templateOperation, identity, Set.empty
  )
}

abstract class MockSimpleRequestContext[BC <: BlockContext](override val timestamp: Instant = Instant.now(),
                                                            override val taskId: Long = 0L,
                                                            override val id: RequestContext.Id = RequestContext.Id.fromString("mock"),
                                                            override val rorKibanaSessionId: CorrelationId = CorrelationId.random,
                                                            override val `type`: Type = Type("default-type"),
                                                            override val action: Action = roAction,
                                                            override val headers: Set[Header] = Set.empty,
                                                            override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                            override val localAddress: Address = Address.from("localhost").get,
                                                            override val method: Method = Method.GET,
                                                            override val uriPath: UriPath = UriPath.from("PATH"),
                                                            override val contentLength: Information = Bytes(0),
                                                            override val content: String = "",
                                                            override val indexAttributes: Set[IndexAttribute] = Set.empty,
                                                            override val allIndicesAndAliases: Set[FullLocalIndexWithAliases] = Set.empty,
                                                            override val allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = Task.now(Set.empty),
                                                            override val allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = Set.empty,
                                                            override val allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] = Task.now(Set.empty),
                                                            override val allTemplates: Set[Template] = Set.empty,
                                                            override val allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = Map.empty,
                                                            override val isCompositeRequest: Boolean = false,
                                                            override val isAllowedForDLS: Boolean = true)
  extends RequestContext {
  override type BLOCK_CONTEXT = BC
}

object MockSimpleRequestContext {
  def apply[BC <: BlockContext](blockContextCreator: RequestContext => BC,
                                customAction: Action): MockSimpleRequestContext[BC] = new MockSimpleRequestContext[BC] {
    override val initialBlockContext: BC = blockContextCreator(this)
    @nowarn override val action: Action = customAction
  }
}