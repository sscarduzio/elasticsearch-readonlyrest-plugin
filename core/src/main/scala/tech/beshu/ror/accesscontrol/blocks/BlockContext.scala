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

import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext.TemplatesTransformation
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.*
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{CombinedGroupsLogic, NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*

sealed trait BlockContext {
  def requestContext: RequestContext

  def userMetadata: UserMetadata

  def responseHeaders: Set[Header]

  def responseTransformations: List[ResponseTransformation]

  def isCurrentGroupEligible(permittedGroupIds: GroupIds): Boolean = {
    if (requestContext.uriPath.isCurrentUserMetadataPath) {
      true
    } else {
      userMetadata.currentGroupId match {
        case Some(preferredGroupId) => permittedGroupIds.matches(preferredGroupId)
        case None => true
      }
    }

  }

  def isCurrentGroupPotentiallyEligible(groupsLogic: GroupsLogic): Boolean = {
    groupsLogic match {
      case _: NegativeGroupsLogic =>
        true
      case logic: PositiveGroupsLogic =>
        isCurrentGroupEligible(logic.permittedGroupIds)
      case logic: CombinedGroupsLogic =>
        isCurrentGroupEligible(logic.positiveGroupsLogic.permittedGroupIds)
    }
  }
}

object BlockContext {

  final case class CurrentUserMetadataRequestBlockContext(override val requestContext: RequestContext,
                                                          override val userMetadata: UserMetadata,
                                                          override val responseHeaders: Set[Header],
                                                          override val responseTransformations: List[ResponseTransformation])
    extends BlockContext

  final case class GeneralNonIndexRequestBlockContext(override val requestContext: RequestContext,
                                                      override val userMetadata: UserMetadata,
                                                      override val responseHeaders: Set[Header],
                                                      override val responseTransformations: List[ResponseTransformation])
    extends BlockContext

  final case class RepositoryRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val responseTransformations: List[ResponseTransformation],
                                                 repositories: Set[RepositoryName])
    extends BlockContext

  final case class SnapshotRequestBlockContext(override val requestContext: RequestContext,
                                               override val userMetadata: UserMetadata,
                                               override val responseHeaders: Set[Header],
                                               override val responseTransformations: List[ResponseTransformation],
                                               snapshots: Set[SnapshotName],
                                               repositories: Set[RepositoryName],
                                               filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                                               allAllowedIndices: Set[ClusterIndexName])
    extends BlockContext

  final case class DataStreamRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val responseTransformations: List[ResponseTransformation],
                                                 dataStreams: Set[DataStreamName],
                                                 backingIndices: DataStreamRequestBlockContext.BackingIndices)
    extends BlockContext

  object DataStreamRequestBlockContext {
    sealed trait BackingIndices

    object BackingIndices {
      final case class IndicesInvolved(filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                                       allAllowedIndices: Set[ClusterIndexName]) extends BackingIndices

      case object IndicesNotInvolved extends BackingIndices
    }
  }

  final case class AliasRequestBlockContext(override val requestContext: RequestContext,
                                            override val userMetadata: UserMetadata,
                                            override val responseHeaders: Set[Header],
                                            override val responseTransformations: List[ResponseTransformation],
                                            aliases: Set[RequestedIndex[ClusterIndexName]],
                                            indices: Set[RequestedIndex[ClusterIndexName]])
    extends BlockContext

  final case class TemplateRequestBlockContext(override val requestContext: RequestContext,
                                               override val userMetadata: UserMetadata,
                                               override val responseHeaders: Set[Header],
                                               override val responseTransformations: List[ResponseTransformation],
                                               templateOperation: TemplateOperation,
                                               responseTemplateTransformation: TemplatesTransformation,
                                               allAllowedIndices: Set[ClusterIndexName])
    extends BlockContext

  object TemplateRequestBlockContext {
    type TemplatesTransformation = Set[Template] => Set[Template]
  }

  final case class GeneralIndexRequestBlockContext(override val requestContext: RequestContext,
                                                   override val userMetadata: UserMetadata,
                                                   override val responseHeaders: Set[Header],
                                                   override val responseTransformations: List[ResponseTransformation],
                                                   filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                                                   allAllowedIndices: Set[ClusterIndexName])
    extends BlockContext

  final case class FilterableRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val responseTransformations: List[ResponseTransformation],
                                                 filteredIndices: Set[RequestedIndex[ClusterIndexName]],
                                                 allAllowedIndices: Set[ClusterIndexName],
                                                 filter: Option[Filter],
                                                 fieldLevelSecurity: Option[FieldLevelSecurity] = None,
                                                 requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.CannotExtractFields)
    extends BlockContext

  final case class FilterableMultiRequestBlockContext(override val requestContext: RequestContext,
                                                      override val userMetadata: UserMetadata,
                                                      override val responseHeaders: Set[Header],
                                                      override val responseTransformations: List[ResponseTransformation],
                                                      indexPacks: List[Indices],
                                                      filter: Option[Filter],
                                                      fieldLevelSecurity: Option[FieldLevelSecurity] = None,
                                                      requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.CannotExtractFields)
    extends BlockContext

  final case class MultiIndexRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val responseTransformations: List[ResponseTransformation],
                                                 indexPacks: List[Indices])
    extends BlockContext

  object MultiIndexRequestBlockContext {
    sealed trait Indices

    object Indices {
      final case class Found(indices: Set[RequestedIndex[ClusterIndexName]]) extends Indices

      case object NotFound extends Indices
    }
  }

  final case class RorApiRequestBlockContext(override val requestContext: RequestContext,
                                             override val userMetadata: UserMetadata,
                                             override val responseHeaders: Set[Header],
                                             override val responseTransformations: List[ResponseTransformation])
    extends BlockContext

  trait HasIndices[B <: BlockContext]

  object HasIndices {

    def apply[B <: BlockContext](implicit instance: HasIndices[B]): HasIndices[B] = instance

    implicit val indicesFromFilterableBlockContext: HasIndices[FilterableRequestBlockContext] =
      new HasIndices[FilterableRequestBlockContext] {}

    implicit val indicesFromGeneralIndexBlockContext: HasIndices[GeneralIndexRequestBlockContext] =
      new HasIndices[GeneralIndexRequestBlockContext] {}

    implicit val indicesFromAliasRequestBlockContext: HasIndices[AliasRequestBlockContext] =
      new HasIndices[AliasRequestBlockContext] {}

    implicit val indicesFromSnapshotRequestBlockContext: HasIndices[SnapshotRequestBlockContext] =
      new HasIndices[SnapshotRequestBlockContext] {}

    implicit def indicesFromDataStreamRequestBlockContext: HasIndices[DataStreamRequestBlockContext] =
      new HasIndices[DataStreamRequestBlockContext] {}
  }

  trait HasIndexPacks[B <: BlockContext] {
    def indexPacks(blockContext: B): List[Indices]
  }

  object HasIndexPacks {

    def apply[B <: BlockContext](implicit instance: HasIndexPacks[B]): HasIndexPacks[B] = instance

    implicit val indexPacksFromFilterableMultiBlockContext: HasIndexPacks[FilterableMultiRequestBlockContext] = new HasIndexPacks[FilterableMultiRequestBlockContext] {
      override def indexPacks(blockContext: FilterableMultiRequestBlockContext): List[Indices] = blockContext.indexPacks
    }

    implicit val indexPacksFromMultiIndexBlockContext: HasIndexPacks[MultiIndexRequestBlockContext] = new HasIndexPacks[MultiIndexRequestBlockContext] {
      override def indexPacks(blockContext: MultiIndexRequestBlockContext): List[Indices] = blockContext.indexPacks
    }

    implicit class Ops[B <: BlockContext : HasIndexPacks](blockContext: B) {
      def indexPacks: List[Indices] = HasIndexPacks[B].indexPacks(blockContext)
    }
  }

  trait HasFilter[B <: BlockContext] {
    def filter(blockContext: B): Option[Filter]
  }

  object HasFilter {

    def apply[B <: BlockContext](implicit instance: HasFilter[B]): HasFilter[B] = instance

    implicit val filterFromFilterableMultiBlockContext: HasFilter[FilterableMultiRequestBlockContext] = new HasFilter[FilterableMultiRequestBlockContext] {
      override def filter(blockContext: FilterableMultiRequestBlockContext): Option[Filter] = blockContext.filter
    }

    implicit val filterFromFilterableRequestBlockContext: HasFilter[FilterableRequestBlockContext] = new HasFilter[FilterableRequestBlockContext] {
      override def filter(blockContext: FilterableRequestBlockContext): Option[Filter] = blockContext.filter
    }

    implicit class Ops[B <: BlockContext : HasFilter](blockContext: B) {
      def filter: Option[Filter] = HasFilter[B].filter(blockContext)
    }
  }

  trait HasFieldLevelSecurity[B <: BlockContext] {
    def fieldLevelSecurity(blockContext: B): Option[FieldLevelSecurity]
  }

  object HasFieldLevelSecurity {

    def apply[B <: BlockContext](implicit instance: HasFieldLevelSecurity[B]): HasFieldLevelSecurity[B] = instance

    implicit val flsFromFilterableMultiBlockContext: HasFieldLevelSecurity[FilterableMultiRequestBlockContext] = new HasFieldLevelSecurity[FilterableMultiRequestBlockContext] {
      override def fieldLevelSecurity(blockContext: FilterableMultiRequestBlockContext): Option[FieldLevelSecurity] = blockContext.fieldLevelSecurity
    }

    implicit val flsFromFilterableRequestBlockContext: HasFieldLevelSecurity[FilterableRequestBlockContext] = new HasFieldLevelSecurity[FilterableRequestBlockContext] {
      override def fieldLevelSecurity(blockContext: FilterableRequestBlockContext): Option[FieldLevelSecurity] = blockContext.fieldLevelSecurity
    }

    implicit class Ops[B <: BlockContext : HasFieldLevelSecurity](blockContext: B) {
      def fieldLevelSecurity: Option[FieldLevelSecurity] = HasFieldLevelSecurity[B].fieldLevelSecurity(blockContext)
    }
  }

  trait AllowsFieldsInRequest[B <: BlockContext] {
    def requestFieldsUsage(blockContext: B): RequestFieldsUsage
  }

  object AllowsFieldsInRequest {

    def apply[B <: BlockContext](implicit instance: AllowsFieldsInRequest[B]): AllowsFieldsInRequest[B] = instance

    implicit val fieldsFromFilterableMultiBlockContext: AllowsFieldsInRequest[FilterableMultiRequestBlockContext] = new AllowsFieldsInRequest[FilterableMultiRequestBlockContext] {
      override def requestFieldsUsage(blockContext: FilterableMultiRequestBlockContext): RequestFieldsUsage = blockContext.requestFieldsUsage
    }

    implicit val fieldsFromFilterableRequestBlockContext: AllowsFieldsInRequest[FilterableRequestBlockContext] = new AllowsFieldsInRequest[FilterableRequestBlockContext] {
      override def requestFieldsUsage(blockContext: FilterableRequestBlockContext): RequestFieldsUsage = blockContext.requestFieldsUsage
    }

    implicit class Ops[B <: BlockContext : AllowsFieldsInRequest](blockContext: B) {
      def requestFieldsUsage: RequestFieldsUsage = AllowsFieldsInRequest[B].requestFieldsUsage(blockContext)
    }
  }

  implicit class BlockContextUpdaterOps[B <: BlockContext : BlockContextUpdater](val blockContext: B) {
    def withUserMetadata(update: UserMetadata => UserMetadata): B =
      BlockContextUpdater[B].withUserMetadata(blockContext, update(blockContext.userMetadata))

    def withAddedResponseHeader(header: Header): B =
      BlockContextUpdater[B].withAddedResponseHeader(blockContext, header)

    def withAddedResponseTransformation(responseTransformation: ResponseTransformation): B =
      BlockContextUpdater[B].withAddedResponseTransformation(blockContext, responseTransformation)
  }

  implicit class RepositoryOperationBlockContextUpdaterOps(val blockContext: RepositoryRequestBlockContext) extends AnyVal {
    def withRepositories(repositories: Set[RepositoryName]): RepositoryRequestBlockContext = {
      RepositoryRequestBlockContextUpdater.withRepositories(blockContext, repositories)
    }
  }

  implicit class SnapshotOperationBlockContextUpdaterOps(val blockContext: SnapshotRequestBlockContext) extends AnyVal {
    def withSnapshots(snapshots: Set[SnapshotName]): SnapshotRequestBlockContext = {
      SnapshotRequestBlockContextUpdater.withSnapshots(blockContext, snapshots)
    }

    def withRepositories(repositories: Set[RepositoryName]): SnapshotRequestBlockContext = {
      SnapshotRequestBlockContextUpdater.withRepositories(blockContext, repositories)
    }
  }

  implicit class DataStreamOperationBlockContextUpdateOps(val blockContext: DataStreamRequestBlockContext) extends AnyVal {
    def withDataStreams(dataStreams: Set[DataStreamName]): DataStreamRequestBlockContext = {
      DataStreamRequestBlockContextUpdater.withDataStreams(blockContext, dataStreams)
    }
  }

  implicit class BlockContextWithIndicesUpdaterOps[B <: BlockContext : BlockContextWithIndicesUpdater](blockContext: B) {
    def withIndices(filteredIndices: Set[RequestedIndex[ClusterIndexName]], allAllowedIndices: Set[ClusterIndexName]): B = {
      BlockContextWithIndicesUpdater[B].withIndices(blockContext, filteredIndices, allAllowedIndices)
    }
  }

  implicit class BlockContextWithIndexPacksUpdaterOps[B <: BlockContext : BlockContextWithIndexPacksUpdater](blockContext: B) {
    def withIndicesPacks(indexPacks: List[Indices]): B = {
      BlockContextWithIndexPacksUpdater[B].withIndexPacks(blockContext, indexPacks)
    }
  }

  implicit class BlockContextWithFilterUpdaterOps[B <: BlockContext : BlockContextWithFilterUpdater](blockContext: B) {
    def withFilter(filter: Filter): B = {
      BlockContextWithFilterUpdater[B].withFilter(blockContext, filter)
    }
  }

  implicit class BlockContextWithFLSUpdaterOps[B <: BlockContext : BlockContextWithFLSUpdater](blockContext: B) {
    def withFields(fields: FieldLevelSecurity): B = {
      BlockContextWithFLSUpdater[B].withFieldLevelSecurity(blockContext, fields)
    }
  }

  implicit class TemplateRequestBlockContextUpdaterOps(val blockContext: TemplateRequestBlockContext) extends AnyVal {
    def withTemplateOperation(templateOperation: TemplateOperation): TemplateRequestBlockContext = {
      TemplateRequestBlockContextUpdater.withTemplateOperation(blockContext, templateOperation)
    }

    def withResponseTemplateTransformation(transformation: Set[Template] => Set[Template]): TemplateRequestBlockContext = {
      TemplateRequestBlockContextUpdater.withResponseTemplateTransformation(blockContext, transformation)
    }

    def withAllAllowedIndices(indices: Set[ClusterIndexName]): TemplateRequestBlockContext = {
      TemplateRequestBlockContextUpdater.withAllAllowedIndices(blockContext, indices)
    }
  }

  implicit class AliasRequestBlockContextUpdaterOps(val blockContext: AliasRequestBlockContext) extends AnyVal {
    def withIndices(indices: Set[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext = {
      AliasRequestBlockContextUpdater.withIndices(blockContext, indices)
    }

    def withAliases(aliases: Set[RequestedIndex[ClusterIndexName]]): AliasRequestBlockContext = {
      AliasRequestBlockContextUpdater.withAliases(blockContext, aliases)
    }
  }

  implicit class IndicesFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def indices: Set[RequestedIndex[ClusterIndexName]] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case _: RepositoryRequestBlockContext => Set.empty
        case bc: SnapshotRequestBlockContext => bc.filteredIndices
        case bc: DataStreamRequestBlockContext => bc.backingIndices match {
          case BackingIndices.IndicesInvolved(filteredIndices, _) => filteredIndices
          case BackingIndices.IndicesNotInvolved => Set.empty
        }
        case bc: TemplateRequestBlockContext =>
          def toRequestedIndices(patterns: Iterable[IndexPattern]) =
            patterns.map(_.value).map(RequestedIndex(_, excluded = false)).toCovariantSet

          bc.templateOperation match {
            case TemplateOperation.GettingLegacyAndIndexTemplates(_, _) => Set.empty
            case TemplateOperation.GettingLegacyTemplates(_) => Set.empty
            case TemplateOperation.AddingLegacyTemplate(_, patterns, aliases) =>
              toRequestedIndices(patterns) ++ aliases
            case TemplateOperation.DeletingLegacyTemplates(_) => Set.empty
            case TemplateOperation.GettingIndexTemplates(_) => Set.empty
            case TemplateOperation.AddingIndexTemplate(_, patterns, aliases) =>
              toRequestedIndices(patterns) ++ aliases
            case TemplateOperation.AddingIndexTemplateAndGetAllowedOnes(_, patterns, aliases, _) =>
              toRequestedIndices(patterns) ++ aliases
            case TemplateOperation.DeletingIndexTemplates(_) => Set.empty
            case TemplateOperation.GettingComponentTemplates(_) => Set.empty
            case TemplateOperation.AddingComponentTemplate(_, aliases) => aliases
            case TemplateOperation.DeletingComponentTemplates(_) => Set.empty
          }
        case bc: AliasRequestBlockContext => bc.indices ++ bc.aliases
        case bc: GeneralIndexRequestBlockContext => bc.filteredIndices
        case bc: FilterableRequestBlockContext => bc.filteredIndices
        case bc: MultiIndexRequestBlockContext => extractIndicesFrom(bc.indexPacks)
        case bc: FilterableMultiRequestBlockContext => extractIndicesFrom(bc.indexPacks)
        case _: RorApiRequestBlockContext => Set.empty
      }
    }

    private def extractIndicesFrom(indexPacks: Iterable[Indices]) = {
      indexPacks
        .flatMap {
          case Indices.Found(indices) => indices
          case Indices.NotFound => Set.empty
        }
        .toCovariantSet
    }
  }

  implicit class RepositoriesFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def repositories: Set[RepositoryName] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case bc: RepositoryRequestBlockContext => bc.repositories
        case bc: SnapshotRequestBlockContext => bc.repositories
        case _: DataStreamRequestBlockContext => Set.empty
        case _: TemplateRequestBlockContext => Set.empty
        case _: AliasRequestBlockContext => Set.empty
        case _: GeneralIndexRequestBlockContext => Set.empty
        case _: MultiIndexRequestBlockContext => Set.empty
        case _: FilterableRequestBlockContext => Set.empty
        case _: FilterableMultiRequestBlockContext => Set.empty
        case _: RorApiRequestBlockContext => Set.empty
      }
    }
  }

  implicit class SnapshotsFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def snapshots: Set[SnapshotName] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case _: RepositoryRequestBlockContext => Set.empty
        case bc: SnapshotRequestBlockContext => bc.snapshots
        case _: DataStreamRequestBlockContext => Set.empty
        case _: TemplateRequestBlockContext => Set.empty
        case _: AliasRequestBlockContext => Set.empty
        case _: GeneralIndexRequestBlockContext => Set.empty
        case _: MultiIndexRequestBlockContext => Set.empty
        case _: FilterableRequestBlockContext => Set.empty
        case _: FilterableMultiRequestBlockContext => Set.empty
        case _: RorApiRequestBlockContext => Set.empty
      }
    }
  }

  implicit class DataStreamsFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def dataStreams: Set[DataStreamName] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case _: RepositoryRequestBlockContext => Set.empty
        case _: SnapshotRequestBlockContext => Set.empty
        case bc: DataStreamRequestBlockContext => bc.dataStreams
        case _: TemplateRequestBlockContext => Set.empty
        case _: AliasRequestBlockContext => Set.empty
        case _: GeneralIndexRequestBlockContext => Set.empty
        case _: MultiIndexRequestBlockContext => Set.empty
        case _: FilterableRequestBlockContext => Set.empty
        case _: FilterableMultiRequestBlockContext => Set.empty
        case _: RorApiRequestBlockContext => Set.empty
      }
    }
  }

  implicit class TemplatesFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def templateOperation: Option[TemplateOperation] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => None
        case _: GeneralNonIndexRequestBlockContext => None
        case _: RepositoryRequestBlockContext => None
        case _: SnapshotRequestBlockContext => None
        case _: DataStreamRequestBlockContext => None
        case bc: TemplateRequestBlockContext => Some(bc.templateOperation)
        case _: AliasRequestBlockContext => None
        case _: GeneralIndexRequestBlockContext => None
        case _: MultiIndexRequestBlockContext => None
        case _: FilterableRequestBlockContext => None
        case _: FilterableMultiRequestBlockContext => None
        case _: RorApiRequestBlockContext => None
      }
    }
  }

  implicit class FieldLevelSecurityFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def fieldLevelSecurity: Option[FieldLevelSecurity] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => None
        case _: GeneralNonIndexRequestBlockContext => None
        case _: RepositoryRequestBlockContext => None
        case _: SnapshotRequestBlockContext => None
        case _: DataStreamRequestBlockContext => None
        case _: TemplateRequestBlockContext => None
        case _: AliasRequestBlockContext => None
        case _: GeneralIndexRequestBlockContext => None
        case _: MultiIndexRequestBlockContext => None
        case bc: FilterableRequestBlockContext => bc.fieldLevelSecurity
        case bc: FilterableMultiRequestBlockContext => bc.fieldLevelSecurity
        case _: RorApiRequestBlockContext => None
      }
    }
  }

  implicit class RandomIndexBasedOnBlockContextIndices[B <: BlockContext : HasIndices](blockContext: B) {

    def randomNonexistentIndex(fromIndices: B => Iterable[RequestedIndex[ClusterIndexName]]): RequestedIndex[ClusterIndexName] = {
      import tech.beshu.ror.accesscontrol.utils.RequestedIndicesOps.*
      fromIndices(blockContext).toList.randomNonexistentIndex()
    }
  }

  implicit class InvolvesIndices(val b: BlockContext) extends AnyVal {
    def involvesIndices: Boolean = b match {
      case _: CurrentUserMetadataRequestBlockContext => hasIndices[CurrentUserMetadataRequestBlockContext]
      case _: GeneralNonIndexRequestBlockContext => hasIndices[GeneralNonIndexRequestBlockContext]
      case _: RepositoryRequestBlockContext => hasIndices[RepositoryRequestBlockContext]
      case _: SnapshotRequestBlockContext => hasIndices[SnapshotRequestBlockContext]
      case _: DataStreamRequestBlockContext => hasIndices[DataStreamRequestBlockContext]
      case _: AliasRequestBlockContext => hasIndices[AliasRequestBlockContext]
      case _: TemplateRequestBlockContext => hasIndices[TemplateRequestBlockContext]
      case _: GeneralIndexRequestBlockContext => hasIndices[GeneralIndexRequestBlockContext]
      case _: FilterableRequestBlockContext => hasIndices[FilterableRequestBlockContext]
      case _: FilterableMultiRequestBlockContext => hasIndices[FilterableMultiRequestBlockContext]
      case _: MultiIndexRequestBlockContext => hasIndices[MultiIndexRequestBlockContext]
      case _: RorApiRequestBlockContext => hasIndices[RorApiRequestBlockContext]
    }

    private implicit def toOption[A](implicit a: A): Option[A] = Some(a)

    private def hasIndices[B <: BlockContext](implicit hasIndices: Option[HasIndices[B]] = None,
                                              hasIndexPacks: Option[HasIndexPacks[B]] = None) = {
      hasIndices.nonEmpty || hasIndexPacks.nonEmpty
    }
  }
}