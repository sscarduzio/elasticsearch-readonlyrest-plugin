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
import com.softwaremill.sttp.Method
import eu.timepit.refined.auto._
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{CurrentUserMetadataRequestBlockContext, FilterableMultiRequestBlockContext, FilterableRequestBlockContext, GeneralIndexRequestBlockContext, GeneralNonIndexRequestBlockContext, MultiIndexRequestBlockContext, RepositoryRequestBlockContext, SnapshotRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.mocks.MockRequestContext.DefaultAction

object MockRequestContext {
  
  val DefaultAction = Action("default-action")
  val AdminAction = Action("cluster:ror/user_metadata/get")
  
  def indices(implicit clock: Clock = Clock.systemUTC()): MockGeneralIndexRequestContext =
    MockGeneralIndexRequestContext(timestamp = clock.instant(), filteredIndices = Set.empty, allAllowedIndices = Set.empty)

  def filterableMulti(implicit clock: Clock = Clock.systemUTC()): MockFilterableMultiRequestContext  =
    MockFilterableMultiRequestContext(timestamp = clock.instant(), indexPacks = List.empty, filter = None, fieldLevelSecurity = None, requestFieldsUsage = RequestFieldsUsage.CannotExtractFields)

  def nonIndices(implicit clock: Clock = Clock.systemUTC()): MockGeneralNonIndexRequestContext =
    MockGeneralNonIndexRequestContext(timestamp = clock.instant())

  def search(implicit clock: Clock = Clock.systemUTC()): MockSearchRequestContext =
    MockSearchRequestContext(timestamp = clock.instant(), indices = Set.empty, allAllowedIndices = Set.empty)

  def repositories(implicit clock: Clock = Clock.systemUTC()): MockRepositoriesRequestContext =
    MockRepositoriesRequestContext(timestamp = clock.instant(), repositories = Set.empty)

  def snapshots(implicit clock: Clock = Clock.systemUTC()): MockSnapshotsRequestContext =
    MockSnapshotsRequestContext(timestamp = clock.instant(), snapshots = Set.empty)

  def metadata(implicit clock: Clock = Clock.systemUTC()): MockUserMetadataRequestContext =
    MockUserMetadataRequestContext(timestamp = clock.instant())

  def readOnly[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, isReadOnly = true, customAction = DefaultAction)

  def readOnlyAdmin[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, isReadOnly = true, customAction = AdminAction)

  def notReadOnly[BC <: BlockContext](blockContextCreator: RequestContext => BC): MockSimpleRequestContext[BC] =
    MockSimpleRequestContext(blockContextCreator, isReadOnly = false, customAction = DefaultAction)
}

final case class MockGeneralIndexRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = DefaultAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method("GET"),
                                                override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isReadOnlyRequest: Boolean = true,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val hasRemoteClusters: Boolean = false,
                                                filteredIndices: Set[IndexName],
                                                allAllowedIndices: Set[IndexName])
  extends RequestContext {
  override type BLOCK_CONTEXT = GeneralIndexRequestBlockContext

  override def initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, filteredIndices, allAllowedIndices
  )

}

final case class MockFilterableMultiRequestContext(override val timestamp: Instant,
                                                   override val taskId: Long = 0L,
                                                   override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = DefaultAction,
                                                   override val headers: Set[Header] = Set.empty,
                                                   override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                   override val localAddress: Address = Address.from("localhost").get,
                                                   override val method: Method = Method("GET"),
                                                   override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                   override val contentLength: Information = Bytes(0),
                                                   override val content: String = "",
                                                   override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                   override val allTemplates: Set[Template] = Set.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isReadOnlyRequest: Boolean = true,
                                                   override val isAllowedForDLS: Boolean = false,
                                                   override val hasRemoteClusters: Boolean = false,
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
                                                   override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                   override val `type`: Type = Type("default-type"),
                                                   override val action: Action = DefaultAction,
                                                   override val headers: Set[Header] = Set.empty,
                                                   override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                   override val localAddress: Address = Address.from("localhost").get,
                                                   override val method: Method = Method("GET"),
                                                   override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                   override val contentLength: Information = Bytes(0),
                                                   override val content: String = "",
                                                   override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                   override val allTemplates: Set[Template] = Set.empty,
                                                   override val isCompositeRequest: Boolean = false,
                                                   override val isReadOnlyRequest: Boolean = true,
                                                   override val isAllowedForDLS: Boolean = true,
                                                   override val hasRemoteClusters: Boolean = false)
  extends RequestContext {

  override type BLOCK_CONTEXT = GeneralNonIndexRequestBlockContext

  override def initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty
  )
}

final case class MockSearchRequestContext(override val timestamp: Instant,
                                          override val taskId: Long = 0L,
                                          override val id: RequestContext.Id = RequestContext.Id("mock"),
                                          override val `type`: Type = Type("default-type"),
                                          override val action: Action = DefaultAction,
                                          override val headers: Set[Header] = Set.empty,
                                          override val remoteAddress: Option[Address] = Address.from("localhost"),
                                          override val localAddress: Address = Address.from("localhost").get,
                                          override val method: Method = Method("GET"),
                                          override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                          override val contentLength: Information = Bytes(0),
                                          override val content: String = "",
                                          override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                          override val allTemplates: Set[Template] = Set.empty,
                                          override val isCompositeRequest: Boolean = false,
                                          override val isReadOnlyRequest: Boolean = true,
                                          override val isAllowedForDLS: Boolean = true,
                                          override val hasRemoteClusters: Boolean = false,
                                          indices: Set[IndexName],
                                          allAllowedIndices: Set[IndexName])
  extends RequestContext {
  override type BLOCK_CONTEXT = FilterableRequestBlockContext

  override def initialBlockContext: FilterableRequestBlockContext = FilterableRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, indices, allAllowedIndices, None
  )
}

final case class MockRepositoriesRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = DefaultAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method("GET"),
                                                override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isReadOnlyRequest: Boolean = true,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val hasRemoteClusters: Boolean = false,
                                                repositories: Set[RepositoryName])
  extends RequestContext {
  override type BLOCK_CONTEXT = RepositoryRequestBlockContext

  override def initialBlockContext: RepositoryRequestBlockContext = RepositoryRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, repositories
  )
}

final case class MockSnapshotsRequestContext(override val timestamp: Instant,
                                             override val taskId: Long = 0L,
                                             override val id: RequestContext.Id = RequestContext.Id("mock"),
                                             override val `type`: Type = Type("default-type"),
                                             override val action: Action = DefaultAction,
                                             override val headers: Set[Header] = Set.empty,
                                             override val remoteAddress: Option[Address] = Address.from("localhost"),
                                             override val localAddress: Address = Address.from("localhost").get,
                                             override val method: Method = Method("GET"),
                                             override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                             override val contentLength: Information = Bytes(0),
                                             override val content: String = "",
                                             override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                             override val allTemplates: Set[Template] = Set.empty,
                                             override val isCompositeRequest: Boolean = false,
                                             override val isReadOnlyRequest: Boolean = true,
                                             override val isAllowedForDLS: Boolean = true,
                                             override val hasRemoteClusters: Boolean = false,
                                             snapshots: Set[SnapshotName])
  extends RequestContext {
  override type BLOCK_CONTEXT = SnapshotRequestBlockContext

  override def initialBlockContext: SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, List.empty, snapshots, Set.empty, Set.empty, Set.empty
  )
}

final case class MockUserMetadataRequestContext(override val timestamp: Instant,
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = DefaultAction,
                                                override val headers: Set[Header] = Set.empty,
                                                override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                override val localAddress: Address = Address.from("localhost").get,
                                                override val method: Method = Method("GET"),
                                                override val uriPath: UriPath = UriPath.currentUserMetadataPath,
                                                override val contentLength: Information = Bytes(0),
                                                override val content: String = "",
                                                override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                override val allTemplates: Set[Template] = Set.empty,
                                                override val isCompositeRequest: Boolean = false,
                                                override val isReadOnlyRequest: Boolean = true,
                                                override val isAllowedForDLS: Boolean = true,
                                                override val hasRemoteClusters: Boolean = false)
  extends RequestContext {
  override type BLOCK_CONTEXT = CurrentUserMetadataRequestBlockContext

  override def initialBlockContext: CurrentUserMetadataRequestBlockContext = CurrentUserMetadataRequestBlockContext(
    this, UserMetadata.empty, Set.empty, List.empty
  )
}

abstract class MockSimpleRequestContext[BC <: BlockContext](override val timestamp: Instant = Instant.now(),
                                                            override val taskId: Long = 0L,
                                                            override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                            override val `type`: Type = Type("default-type"),
                                                            override val action: Action = DefaultAction,
                                                            override val headers: Set[Header] = Set.empty,
                                                            override val remoteAddress: Option[Address] = Address.from("localhost"),
                                                            override val localAddress: Address = Address.from("localhost").get,
                                                            override val method: Method = Method("GET"),
                                                            override val uriPath: UriPath = UriPath("PATH"),
                                                            override val contentLength: Information = Bytes(0),
                                                            override val content: String = "",
                                                            override val allIndicesAndAliases: Set[IndexWithAliases] = Set.empty,
                                                            override val allTemplates: Set[Template] = Set.empty,
                                                            override val isCompositeRequest: Boolean = false,
                                                            override val isAllowedForDLS: Boolean = true,
                                                            override val hasRemoteClusters: Boolean = false)
  extends RequestContext {
  override type BLOCK_CONTEXT = BC
}

object MockSimpleRequestContext {
  def apply[BC <: BlockContext](blockContextCreator: RequestContext => BC,
                                isReadOnly: Boolean,
                                customAction: Action): MockSimpleRequestContext[BC] = new MockSimpleRequestContext[BC] {
    override val initialBlockContext: BC = blockContextCreator(this)
    override val isReadOnlyRequest: Boolean = isReadOnly
    override val action: Action = customAction
  }
}