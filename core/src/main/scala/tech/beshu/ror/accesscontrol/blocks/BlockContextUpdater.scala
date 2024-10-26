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

import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.*

sealed trait BlockContextUpdater[B <: BlockContext] {

  def emptyBlockContext(blockContext: B): B

  def withUserMetadata(blockContext: B, userMetadata: UserMetadata): B

  def withAddedResponseHeader(blockContext: B, header: Header): B

  def withAddedResponseTransformation(blockContext: B, responseTransformation: ResponseTransformation): B
}

object BlockContextUpdater {

  def apply[B <: BlockContext](implicit instance: BlockContextUpdater[B]): BlockContextUpdater[B] = instance

  implicit object CurrentUserMetadataRequestBlockContextUpdater
    extends BlockContextUpdater[CurrentUserMetadataRequestBlockContext] {

    override def emptyBlockContext(blockContext: CurrentUserMetadataRequestBlockContext): CurrentUserMetadataRequestBlockContext =
      CurrentUserMetadataRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty)

    override def withUserMetadata(blockContext: CurrentUserMetadataRequestBlockContext,
                                  userMetadata: UserMetadata): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: CurrentUserMetadataRequestBlockContext,
                                         header: Header): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: CurrentUserMetadataRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): CurrentUserMetadataRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)
  }

  implicit object GeneralNonIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralNonIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralNonIndexRequestBlockContext): GeneralNonIndexRequestBlockContext =
      GeneralNonIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty)

    override def withUserMetadata(blockContext: GeneralNonIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralNonIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralNonIndexRequestBlockContext,
                                         header: Header): GeneralNonIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: GeneralNonIndexRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): GeneralNonIndexRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)
  }

  implicit object RepositoryRequestBlockContextUpdater
    extends BlockContextUpdater[RepositoryRequestBlockContext] {

    override def emptyBlockContext(blockContext: RepositoryRequestBlockContext): RepositoryRequestBlockContext =
      RepositoryRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty)

    override def withUserMetadata(blockContext: RepositoryRequestBlockContext,
                                  userMetadata: UserMetadata): RepositoryRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: RepositoryRequestBlockContext,
                                         header: Header): RepositoryRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: RepositoryRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): RepositoryRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withRepositories(blockContext: RepositoryRequestBlockContext,
                         repositories: Set[RepositoryName]): RepositoryRequestBlockContext =
      blockContext.copy(repositories = repositories)
  }

  implicit object SnapshotRequestBlockContextUpdater
    extends BlockContextUpdater[SnapshotRequestBlockContext] {
    override def emptyBlockContext(blockContext: SnapshotRequestBlockContext): SnapshotRequestBlockContext =
      SnapshotRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: SnapshotRequestBlockContext,
                                  userMetadata: UserMetadata): SnapshotRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: SnapshotRequestBlockContext,
                                         header: Header): SnapshotRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: SnapshotRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): SnapshotRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withSnapshots(blockContext: SnapshotRequestBlockContext,
                      snapshots: Set[SnapshotName]): SnapshotRequestBlockContext =
      blockContext.copy(snapshots = snapshots)

    def withRepositories(blockContext: SnapshotRequestBlockContext,
                         repositories: Set[RepositoryName]): SnapshotRequestBlockContext =
      blockContext.copy(repositories = repositories)

    def withIndices(blockContext: SnapshotRequestBlockContext,
                    indices: Iterable[RequestedIndex[ClusterIndexName]]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = indices)
  }

  implicit object DataStreamRequestBlockContextUpdater
    extends BlockContextUpdater[DataStreamRequestBlockContext] {

    override def emptyBlockContext(blockContext: DataStreamRequestBlockContext): DataStreamRequestBlockContext =
      DataStreamRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, DataStreamRequestBlockContext.BackingIndices.IndicesNotInvolved)

    override def withUserMetadata(blockContext: DataStreamRequestBlockContext,
                                  userMetadata: UserMetadata): DataStreamRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: DataStreamRequestBlockContext,
                                         header: Header): DataStreamRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: DataStreamRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): DataStreamRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withDataStreams(blockContext: DataStreamRequestBlockContext,
                        dataStreams: Set[DataStreamName]): DataStreamRequestBlockContext = {
      blockContext.copy(dataStreams = dataStreams)
    }
  }

  implicit object TemplateRequestBlockContextUpdater
    extends BlockContextUpdater[TemplateRequestBlockContext] {

    override def emptyBlockContext(blockContext: TemplateRequestBlockContext): TemplateRequestBlockContext =
      TemplateRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, blockContext.templateOperation, identity, Set.empty)

    override def withUserMetadata(blockContext: TemplateRequestBlockContext,
                                  userMetadata: UserMetadata): TemplateRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: TemplateRequestBlockContext,
                                         header: Header): TemplateRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: TemplateRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): TemplateRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withTemplateOperation(blockContext: TemplateRequestBlockContext,
                              templateOperation: TemplateOperation): TemplateRequestBlockContext =
      blockContext.copy(templateOperation = templateOperation)

    def withResponseTemplateTransformation(blockContext: TemplateRequestBlockContext,
                                           transformation: Set[Template] => Set[Template]): TemplateRequestBlockContext =
      blockContext.copy(responseTemplateTransformation = transformation)

    def withAllAllowedIndices(blockContext: TemplateRequestBlockContext,
                              indices: Set[ClusterIndexName]): TemplateRequestBlockContext =
      blockContext.copy(allAllowedIndices = indices)

  }

  implicit object AliasRequestBlockContextUpdater
    extends BlockContextUpdater[AliasRequestBlockContext] {

    override def emptyBlockContext(blockContext: AliasRequestBlockContext): AliasRequestBlockContext =
      AliasRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: AliasRequestBlockContext,
                                  userMetadata: UserMetadata): AliasRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: AliasRequestBlockContext,
                                         header: Header): AliasRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: AliasRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): AliasRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withIndices(blockContext: AliasRequestBlockContext,
                    indices: Iterable[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext =
      blockContext.copy(indices = indices)

    def withAliases(blockContext: AliasRequestBlockContext,
                    aliases: Iterable[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext =
      blockContext.copy(aliases = aliases)
  }

  implicit object GeneralIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralIndexRequestBlockContext): GeneralIndexRequestBlockContext =
      GeneralIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

    override def withUserMetadata(blockContext: GeneralIndexRequestBlockContext,
                                  userMetadata: UserMetadata): GeneralIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: GeneralIndexRequestBlockContext,
                                         header: Header): GeneralIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: GeneralIndexRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): GeneralIndexRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

  }

  implicit object MultiIndexRequestBlockContextUpdater
    extends BlockContextUpdater[MultiIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: MultiIndexRequestBlockContext): MultiIndexRequestBlockContext =
      MultiIndexRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, List.empty)

    override def withUserMetadata(blockContext: MultiIndexRequestBlockContext,
                                  userMetadata: UserMetadata): MultiIndexRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: MultiIndexRequestBlockContext,
                                         header: Header): MultiIndexRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: MultiIndexRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): MultiIndexRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

  }

  implicit object FilterableRequestBlockContextUpdater
    extends BlockContextUpdater[FilterableRequestBlockContext] {

    override def emptyBlockContext(blockContext: FilterableRequestBlockContext): FilterableRequestBlockContext =
      FilterableRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty, None, None)

    override def withUserMetadata(blockContext: FilterableRequestBlockContext,
                                  userMetadata: UserMetadata): FilterableRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: FilterableRequestBlockContext,
                                         header: Header): FilterableRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: FilterableRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): FilterableRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

  }

  implicit object FilterableMultiRequestBlockContextUpdater
    extends BlockContextUpdater[FilterableMultiRequestBlockContext] {

    override def emptyBlockContext(blockContext: FilterableMultiRequestBlockContext): FilterableMultiRequestBlockContext =
      FilterableMultiRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty, List.empty, None)

    override def withUserMetadata(blockContext: FilterableMultiRequestBlockContext,
                                  userMetadata: UserMetadata): FilterableMultiRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: FilterableMultiRequestBlockContext,
                                         header: Header): FilterableMultiRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: FilterableMultiRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): FilterableMultiRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)
  }

  implicit object RorApiRequestBlockContextUpdater
    extends BlockContextUpdater[RorApiRequestBlockContext] {

    override def emptyBlockContext(blockContext: RorApiRequestBlockContext): RorApiRequestBlockContext =
      RorApiRequestBlockContext(blockContext.requestContext, UserMetadata.empty, Set.empty, List.empty)

    override def withUserMetadata(blockContext: RorApiRequestBlockContext,
                                  userMetadata: UserMetadata): RorApiRequestBlockContext =
      blockContext.copy(userMetadata = userMetadata)

    override def withAddedResponseHeader(blockContext: RorApiRequestBlockContext,
                                         header: Header): RorApiRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: RorApiRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): RorApiRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)
  }
}

abstract class BlockContextWithIndicesUpdater[B <: BlockContext : HasIndices] {

  def withIndices(blockContext: B,
                  filteredIndices: Iterable[RequestedIndex[ClusterIndexName]],
                  allAllowedIndices: Set[ClusterIndexName]): B
}

object BlockContextWithIndicesUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithIndicesUpdater[B]) = ev

  implicit object FilterableRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[FilterableRequestBlockContext] {

    def withIndices(blockContext: FilterableRequestBlockContext,
                    filteredIndices: Iterable[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): FilterableRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object GeneralIndexRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[GeneralIndexRequestBlockContext] {

    def withIndices(blockContext: GeneralIndexRequestBlockContext,
                    filteredIndices: Iterable[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): GeneralIndexRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object SnapshotRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[SnapshotRequestBlockContext] {

    def withIndices(blockContext: SnapshotRequestBlockContext,
                    filteredIndices: Iterable[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object DataStreamRequestBlocContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[DataStreamRequestBlockContext] {

    def withIndices(blockContext: DataStreamRequestBlockContext,
                    filteredIndices: Iterable[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): DataStreamRequestBlockContext =
      blockContext.copy(backingIndices = DataStreamRequestBlockContext.BackingIndices.IndicesInvolved(
        filteredIndices = filteredIndices,
        allAllowedIndices = allAllowedIndices
      ))
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