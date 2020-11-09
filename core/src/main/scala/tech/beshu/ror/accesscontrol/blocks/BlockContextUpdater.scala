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
package tech.beshu.ror.accesscontrol.blocks

import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext._
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain._

sealed trait BlockContextUpdater[B <: BlockContext] {

  def emptyBlockContext(blockContext: B): B

  def withUserMetadata(blockContext: B, userMetadata: UserMetadata): B

  def withAddedResponseHeader(blockContext: B, header: Header): B
}

object BlockContextUpdater {

  def apply[B <: BlockContext](implicit instance: BlockContextUpdater[B]): BlockContextUpdater[B] = instance

  implicit object CurrentUserMetadataRequestBlockContextUpdater
    extends BlockContextUpdater[CurrentUserMetadataRequestBlockContext] {

    override def emptyBlockContext(blockContext: CurrentUserMetadataRequestBlockContext): CurrentUserMetadataRequestBlockContext =
      CurrentUserMetadataRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty)

    override def withUserMetadata(blockContext: CurrentUserMetadataRequestBlockContext,
                                  userMetadata: UserMetadata): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: CurrentUserMetadataRequestBlockContext,
                                         header: Header): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)
  }

  implicit object GeneralNonIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralNonIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralNonIndexRequestBlockContext): GeneralNonIndexRequestBlockContext =
      GeneralNonIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty)

    override def withUserMetadata(blockContext: GeneralNonIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralNonIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralNonIndexRequestBlockContext,
                                         header: Header): GeneralNonIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)
  }

  implicit object RepositoryRequestBlockContextUpdater
    extends BlockContextUpdater[RepositoryRequestBlockContext] {

    override def emptyBlockContext(blockContext: RepositoryRequestBlockContext): RepositoryRequestBlockContext =
      RepositoryRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: RepositoryRequestBlockContext,
                                  userMetadata: UserMetadata): RepositoryRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: RepositoryRequestBlockContext,
                                         header: Header): RepositoryRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    def withRepositories(blockContext: RepositoryRequestBlockContext,
                         repositories: Set[RepositoryName]): RepositoryRequestBlockContext =
      blockContext.copy(repositories = repositories)
  }

  implicit object SnapshotRequestBlockContextUpdater
    extends BlockContextUpdater[SnapshotRequestBlockContext] {
    override def emptyBlockContext(blockContext: SnapshotRequestBlockContext): SnapshotRequestBlockContext =
      SnapshotRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: SnapshotRequestBlockContext,
                                  userMetadata: UserMetadata): SnapshotRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: SnapshotRequestBlockContext,
                                         header: Header): SnapshotRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    def withSnapshots(blockContext: SnapshotRequestBlockContext,
                      snapshots: Set[SnapshotName]): SnapshotRequestBlockContext =
      blockContext.copy(snapshots = snapshots)

    def withRepositories(blockContext: SnapshotRequestBlockContext,
                         repositories: Set[RepositoryName]): SnapshotRequestBlockContext =
      blockContext.copy(repositories = repositories)

    def withIndices(blockContext: SnapshotRequestBlockContext,
                    indices: Set[IndexName]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = indices)
  }

  implicit object TemplateRequestBlockContextUpdater
    extends BlockContextUpdater[TemplateRequestBlockContext] {

    override def emptyBlockContext(blockContext: TemplateRequestBlockContext): TemplateRequestBlockContext =
      TemplateRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, blockContext.templates)

    override def withUserMetadata(blockContext: TemplateRequestBlockContext,
                                  userMetadata: UserMetadata): TemplateRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: TemplateRequestBlockContext,
                                         header: Header): TemplateRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    def withTemplates(blockContext: TemplateRequestBlockContext,
                      templates: Set[Template]): TemplateRequestBlockContext =
      blockContext.copy(templates = templates)
  }

  implicit object AliasRequestBlockContextUpdater
    extends BlockContextUpdater[AliasRequestBlockContext] {

    override def emptyBlockContext(blockContext: AliasRequestBlockContext): AliasRequestBlockContext =
      AliasRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: AliasRequestBlockContext,
                                  userMetadata: UserMetadata): AliasRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: AliasRequestBlockContext,
                                         header: Header): AliasRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    def withIndices(blockContext: AliasRequestBlockContext,
                    indices: Set[IndexName]): AliasRequestBlockContext =
      blockContext.copy(indices = indices)

    def withAliases(blockContext: AliasRequestBlockContext,
                    aliases: Set[IndexName]): AliasRequestBlockContext =
      blockContext.copy(aliases = aliases)
  }

  implicit object GeneralIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralIndexRequestBlockContext): GeneralIndexRequestBlockContext =
      GeneralIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: GeneralIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralIndexRequestBlockContext,
                                         header: Header): GeneralIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)
  }

  implicit object MultiIndexRequestBlockContextUpdater
    extends BlockContextUpdater[MultiIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: MultiIndexRequestBlockContext): MultiIndexRequestBlockContext =
      MultiIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty)

    override def withUserMetadata(blockContext: MultiIndexRequestBlockContext,
                                  userMetadata: UserMetadata): MultiIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: MultiIndexRequestBlockContext,
                                         header: Header): MultiIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

  }

  implicit object FilterableRequestBlockContextUpdater
    extends BlockContextUpdater[FilterableRequestBlockContext] {

    override def emptyBlockContext(blockContext: FilterableRequestBlockContext): FilterableRequestBlockContext =
      FilterableRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, Set.empty, Set.empty, None, None)

    override def withUserMetadata(blockContext: FilterableRequestBlockContext,
                                  userMetadata: UserMetadata): FilterableRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: FilterableRequestBlockContext,
                                         header: Header): FilterableRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)
  }

  implicit object FilterableMultiRequestBlockContextUpdater
    extends BlockContextUpdater[FilterableMultiRequestBlockContext] {

    override def emptyBlockContext(blockContext: FilterableMultiRequestBlockContext): FilterableMultiRequestBlockContext =
      FilterableMultiRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, None)

    override def withUserMetadata(blockContext: FilterableMultiRequestBlockContext,
                                  userMetadata: UserMetadata): FilterableMultiRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: FilterableMultiRequestBlockContext,
                                         header: Header): FilterableMultiRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)
  }
}

abstract class BlockContextWithIndicesUpdater[B <: BlockContext: HasIndices] {

  def withIndices(blockContext: B, filteredIndices: Set[IndexName], allAllowedIndices: Set[IndexName]): B
}

object BlockContextWithIndicesUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithIndicesUpdater[B]) = ev

  implicit object FilterableRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[FilterableRequestBlockContext] {

    def withIndices(blockContext: FilterableRequestBlockContext,
                    filteredIndices: Set[IndexName],
                    allAllowedIndices: Set[IndexName]): FilterableRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object GeneralIndexRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[GeneralIndexRequestBlockContext] {

    def withIndices(blockContext: GeneralIndexRequestBlockContext,
                    filteredIndices: Set[IndexName],
                    allAllowedIndices: Set[IndexName]): GeneralIndexRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object SnapshotRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[SnapshotRequestBlockContext] {

    def withIndices(blockContext: SnapshotRequestBlockContext,
                    filteredIndices: Set[IndexName],
                    allAllowedIndices: Set[IndexName]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }
}

abstract class BlockContextWithIndexPacksUpdater[B <: BlockContext : HasIndexPacks] {

  def withIndexPacks(blockContext: B, indexPacks: List[Indices]): B
}

object BlockContextWithIndexPacksUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithIndexPacksUpdater[B]) = ev

  implicit object FilterableMultiRequestBlockContextWithIndexPacksUpdater
    extends BlockContextWithIndexPacksUpdater[FilterableMultiRequestBlockContext] {

    def withIndexPacks(blockContext: FilterableMultiRequestBlockContext,
                       indexPacks: List[Indices]): FilterableMultiRequestBlockContext =
      blockContext.copy(indexPacks = indexPacks)
  }

  implicit object MultiIndexRequestBlockContextWithIndexPacksUpdater
    extends BlockContextWithIndexPacksUpdater[MultiIndexRequestBlockContext] {

    def withIndexPacks(blockContext: MultiIndexRequestBlockContext,
                       indexPacks: List[Indices]): MultiIndexRequestBlockContext =
      blockContext.copy(indexPacks = indexPacks)
  }
}

abstract class BlockContextWithFilterUpdater[B <: BlockContext : HasFilter] {

  def withFilter(blockContext: B, filter: Filter): B
}

object BlockContextWithFilterUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithFilterUpdater[B]) = ev

  implicit object FilterableMultiRequestBlockContextWithFilterUpdater
    extends BlockContextWithFilterUpdater[FilterableMultiRequestBlockContext] {

    def withFilter(blockContext: FilterableMultiRequestBlockContext,
                   filter: Filter): FilterableMultiRequestBlockContext =
      blockContext.copy(filter = Some(filter))
  }

  implicit object FilterableBlockContextWithFilterUpdater
    extends BlockContextWithFilterUpdater[FilterableRequestBlockContext] {

    def withFilter(blockContext: FilterableRequestBlockContext,
                   filter: Filter): FilterableRequestBlockContext =
      blockContext.copy(filter = Some(filter))
  }
}

abstract class BlockContextWithFLSUpdater[B <: BlockContext : HasFieldLevelSecurity] {

  def withFieldLevelSecurity(blockContext: B, fieldLevelSecurity: FieldLevelSecurity): B
}

object BlockContextWithFLSUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithFLSUpdater[B]) = ev

  implicit object FilterableMultiRequestBlockContextWithFieldsUpdater
    extends BlockContextWithFLSUpdater[FilterableMultiRequestBlockContext] {

    def withFieldLevelSecurity(blockContext: FilterableMultiRequestBlockContext,
                               fieldLevelSecurity: FieldLevelSecurity): FilterableMultiRequestBlockContext =
      blockContext.copy(fieldLevelSecurity = Some(fieldLevelSecurity))
  }

  implicit object FilterableBlockContextWithFieldsUpdater
    extends BlockContextWithFLSUpdater[FilterableRequestBlockContext] {

    def withFieldLevelSecurity(blockContext: FilterableRequestBlockContext,
                               fieldLevelSecurity: FieldLevelSecurity): FilterableRequestBlockContext =
      blockContext.copy(fieldLevelSecurity = Some(fieldLevelSecurity))
  }
}