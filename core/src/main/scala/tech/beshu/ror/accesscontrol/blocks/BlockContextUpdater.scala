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

import scala.annotation.nowarn
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.syntax.*

sealed trait BlockContextUpdater[B <: BlockContext] {

  def emptyBlockContext(blockContext: B): B

  def withBlockMetadata(blockContext: B, blockMetadata: BlockMetadata): B

  def withAddedResponseHeader(blockContext: B, header: Header): B

  def withAddedResponseTransformation(blockContext: B, responseTransformation: ResponseTransformation): B
}

object BlockContextUpdater {

  def apply[B <: BlockContext](implicit instance: BlockContextUpdater[B]): BlockContextUpdater[B] = instance

  implicit object UserMetadataRequestBlockContextUpdater
    extends BlockContextUpdater[UserMetadataRequestBlockContext] {

    override def emptyBlockContext(blockContext: UserMetadataRequestBlockContext): UserMetadataRequestBlockContext =
      UserMetadataRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty)

    override def withBlockMetadata(blockContext: UserMetadataRequestBlockContext,
                                   blockMetadata: BlockMetadata): UserMetadataRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

    override def withAddedResponseHeader(blockContext: UserMetadataRequestBlockContext,
                                         header: Header): UserMetadataRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: UserMetadataRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): UserMetadataRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)
  }

  implicit object GeneralNonIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralNonIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralNonIndexRequestBlockContext): GeneralNonIndexRequestBlockContext =
      GeneralNonIndexRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty)

    override def withBlockMetadata(blockContext: GeneralNonIndexRequestBlockContext,
                                   blockMetadata: BlockMetadata): GeneralNonIndexRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      RepositoryRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty)

    override def withBlockMetadata(blockContext: RepositoryRequestBlockContext,
                                   blockMetadata: BlockMetadata): RepositoryRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      SnapshotRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty, Set.empty, Set.empty)

    override def withBlockMetadata(blockContext: SnapshotRequestBlockContext,
                                   blockMetadata: BlockMetadata): SnapshotRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
                    indices: Set[RequestedIndex[ClusterIndexName]]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = indices)
  }

  implicit object DataStreamRequestBlockContextUpdater
    extends BlockContextUpdater[DataStreamRequestBlockContext] {

    override def emptyBlockContext(blockContext: DataStreamRequestBlockContext): DataStreamRequestBlockContext =
      DataStreamRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty, DataStreamRequestBlockContext.BackingIndices.IndicesNotInvolved)

    override def withBlockMetadata(blockContext: DataStreamRequestBlockContext,
                                   blockMetadata: BlockMetadata): DataStreamRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      TemplateRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, blockContext.templateOperation, identity, Set.empty)

    override def withBlockMetadata(blockContext: TemplateRequestBlockContext,
                                   blockMetadata: BlockMetadata): TemplateRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      AliasRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty)

    override def withBlockMetadata(blockContext: AliasRequestBlockContext,
                                   blockMetadata: BlockMetadata): AliasRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

    override def withAddedResponseHeader(blockContext: AliasRequestBlockContext,
                                         header: Header): AliasRequestBlockContext =
      blockContext.copy(responseHeaders = blockContext.responseHeaders + header)

    override def withAddedResponseTransformation(blockContext: AliasRequestBlockContext,
                                                 responseTransformation: ResponseTransformation): AliasRequestBlockContext =
      blockContext.copy(responseTransformations = responseTransformation :: blockContext.responseTransformations)

    def withIndices(blockContext: AliasRequestBlockContext,
                    indices: Set[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext =
      blockContext.copy(indices = indices)

    def withAliases(blockContext: AliasRequestBlockContext,
                    aliases: Set[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext =
      blockContext.copy(aliases = aliases)
  }

  implicit object GeneralIndexRequestBlockContextUpdater
    extends BlockContextUpdater[GeneralIndexRequestBlockContext] {

    override def emptyBlockContext(blockContext: GeneralIndexRequestBlockContext): GeneralIndexRequestBlockContext =
      GeneralIndexRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty, Set.empty)

    override def withBlockMetadata(blockContext: GeneralIndexRequestBlockContext,
                                   blockMetadata: BlockMetadata): GeneralIndexRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      MultiIndexRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, List.empty)

    override def withBlockMetadata(blockContext: MultiIndexRequestBlockContext,
                                   blockMetadata: BlockMetadata): MultiIndexRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      FilterableRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, Set.empty, Set.empty, None, None)

    override def withBlockMetadata(blockContext: FilterableRequestBlockContext,
                                   blockMetadata: BlockMetadata): FilterableRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      FilterableMultiRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty, List.empty, None)

    override def withBlockMetadata(blockContext: FilterableMultiRequestBlockContext,
                                   blockMetadata: BlockMetadata): FilterableMultiRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
      RorApiRequestBlockContext(blockContext.block, blockContext.requestContext, BlockMetadata.empty, Set.empty, List.empty)

    override def withBlockMetadata(blockContext: RorApiRequestBlockContext,
                                   blockMetadata: BlockMetadata): RorApiRequestBlockContext =
      blockContext.copy(blockMetadata = blockMetadata)

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
                  filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                  allAllowedIndices: Set[ClusterIndexName]): B

  @nowarn("msg=unused explicit parameter")
  def withClusters(blockContext: B,
                   allAllowedClusters: Set[ClusterName.Full]): B = blockContext
}

object BlockContextWithIndicesUpdater {
  def apply[B <: BlockContext](implicit ev: BlockContextWithIndicesUpdater[B]) = ev

  implicit object FilterableRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[FilterableRequestBlockContext] {

    def withIndices(blockContext: FilterableRequestBlockContext,
                    filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): FilterableRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object GeneralIndexRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[GeneralIndexRequestBlockContext] {

    def withIndices(blockContext: GeneralIndexRequestBlockContext,
                    filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): GeneralIndexRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)

    override def withClusters(blockContext: GeneralIndexRequestBlockContext,
                              allAllowedClusters: Set[ClusterName.Full]): GeneralIndexRequestBlockContext =
      blockContext.copy(allAllowedClusters = allAllowedClusters)

  }

  implicit object SnapshotRequestBlockContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[SnapshotRequestBlockContext] {

    def withIndices(blockContext: SnapshotRequestBlockContext,
                    filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): SnapshotRequestBlockContext =
      blockContext.copy(filteredIndices = filteredIndices, allAllowedIndices = allAllowedIndices)
  }

  implicit object DataStreamRequestBlocContextWithIndicesUpdater
    extends BlockContextWithIndicesUpdater[DataStreamRequestBlockContext] {

    def withIndices(blockContext: DataStreamRequestBlockContext,
                    filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                    allAllowedIndices: Set[ClusterIndexName]): DataStreamRequestBlockContext =
      blockContext.copy(backingIndices = DataStreamRequestBlockContext.BackingIndices.IndicesInvolved(
        filteredIndices = filteredIndices,
        allAllowedIndices = allAllowedIndices
      ))
  }
}

@nowarn("msg=unused implicit parameter")
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

@nowarn("msg=unused implicit parameter")
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

@nowarn("msg=unused implicit parameter")
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