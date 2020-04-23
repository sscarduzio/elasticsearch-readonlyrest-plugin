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

import java.time.Instant

import com.softwaremill.sttp.Method
import squants.information.{Bytes, Information}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{CurrentUserMetadataRequestBlockContext, GeneralIndexRequestBlockContext, RepositoryRequestBlockContext, SnapshotRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext

object MockRequestContext {
  def indices: MockGeneralIndexRequestContext = MockGeneralIndexRequestContext(indices = Set.empty)
  def repositories: MockRepositoriesRequestContext = MockRepositoriesRequestContext(repositories = Set.empty)
  def snapshots: MockSnapshotsRequestContext = MockSnapshotsRequestContext(snapshots = Set.empty)
  def metadata: MockUserMetadataRequestContext = MockUserMetadataRequestContext()
}

final case class MockGeneralIndexRequestContext(override val timestamp: Instant = Instant.now(),
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action("default-action"),
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
                                                indices: Set[IndexName])
  extends RequestContext {
  override type BLOCK_CONTEXT = GeneralIndexRequestBlockContext

  override def initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this, UserMetadata.from(this), Set.empty, Set.empty, indices
  )
}

final case class MockRepositoriesRequestContext(override val timestamp: Instant = Instant.now(),
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action("default-action"),
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
    this, UserMetadata.from(this), Set.empty, Set.empty, repositories
  )
}

final case class MockSnapshotsRequestContext(override val timestamp: Instant = Instant.now(),
                                             override val taskId: Long = 0L,
                                             override val id: RequestContext.Id = RequestContext.Id("mock"),
                                             override val `type`: Type = Type("default-type"),
                                             override val action: Action = Action("default-action"),
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
    this, UserMetadata.from(this), Set.empty, Set.empty, snapshots, Set.empty, Set.empty
  )
}

final case class MockUserMetadataRequestContext(override val timestamp: Instant = Instant.now(),
                                                override val taskId: Long = 0L,
                                                override val id: RequestContext.Id = RequestContext.Id("mock"),
                                                override val `type`: Type = Type("default-type"),
                                                override val action: Action = Action("default-action"),
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
    this, UserMetadata.empty, Set.empty, Set.empty
  )
}
