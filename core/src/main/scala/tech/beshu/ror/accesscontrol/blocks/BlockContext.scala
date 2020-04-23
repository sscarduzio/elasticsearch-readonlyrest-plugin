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
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{GeneralIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext

sealed trait BlockContext {
  def requestContext: RequestContext

  def userMetadata: UserMetadata

  def responseHeaders: Set[Header]

  def contextHeaders: Set[Header]
}
object BlockContext {

  final case class CurrentUserMetadataRequestBlockContext(override val requestContext: RequestContext,
                                                          override val userMetadata: UserMetadata,
                                                          override val responseHeaders: Set[Header],
                                                          override val contextHeaders: Set[Header])
    extends BlockContext

  final case class GeneralNonIndexRequestBlockContext(override val requestContext: RequestContext,
                                                      override val userMetadata: UserMetadata,
                                                      override val responseHeaders: Set[Header],
                                                      override val contextHeaders: Set[Header])
    extends BlockContext

  final case class RepositoryRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val contextHeaders: Set[Header],
                                                 repositories: Set[RepositoryName])
    extends BlockContext

  final case class SnapshotRequestBlockContext(override val requestContext: RequestContext,
                                               override val userMetadata: UserMetadata,
                                               override val responseHeaders: Set[Header],
                                               override val contextHeaders: Set[Header],
                                               snapshots: Set[SnapshotName],
                                               repositories: Set[RepositoryName],
                                               indices: Set[IndexName])
    extends BlockContext

  final case class TemplateRequestBlockContext(override val requestContext: RequestContext,
                                               override val userMetadata: UserMetadata,
                                               override val responseHeaders: Set[Header],
                                               override val contextHeaders: Set[Header],
                                               templates: Set[Template])
    extends BlockContext

  final case class GeneralIndexRequestBlockContext(override val requestContext: RequestContext,
                                                   override val userMetadata: UserMetadata,
                                                   override val responseHeaders: Set[Header],
                                                   override val contextHeaders: Set[Header],
                                                   indices: Set[IndexName])
    extends BlockContext

  final case class MultiIndexRequestBlockContext(override val requestContext: RequestContext,
                                                 override val userMetadata: UserMetadata,
                                                 override val responseHeaders: Set[Header],
                                                 override val contextHeaders: Set[Header],
                                                 indexPacks: List[Indices])
    extends BlockContext
  object MultiIndexRequestBlockContext {
    sealed trait Indices
    object Indices {
      final case class Found(indices: Set[IndexName]) extends Indices
      case object NotFound extends Indices
    }
  }

  implicit class BlockContextUpdaterOps[B <: BlockContext : BlockContextUpdater](val blockContext: B) {
    def withUserMetadata(update: UserMetadata => UserMetadata): B =
      BlockContextUpdater[B].withUserMetadata(blockContext, update(blockContext.userMetadata))

    def withAddedContextHeader(header: Header): B =
      BlockContextUpdater[B].withAddedContextHeader(blockContext, header)

    def withAddedResponseHeader(header: Header): B =
      BlockContextUpdater[B].withAddedResponseHeader(blockContext, header)
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

  implicit class GeneralIndexRequestBlockContextUpdaterOps(val blockContext: GeneralIndexRequestBlockContext) extends AnyVal {
    def withIndices(indices: Set[IndexName]): GeneralIndexRequestBlockContext = {
      GeneralIndexRequestBlockContextUpdater.withIndices(blockContext, indices)
    }
  }

  implicit class MultiIndexRequestBlockContextUpdaterOps(val blockContext: MultiIndexRequestBlockContext) extends AnyVal {
    def withIndicesPacks(indexPacks: List[Indices]): MultiIndexRequestBlockContext = {
      MultiIndexRequestBlockContextUpdater.withIndexPacks(blockContext, indexPacks)
    }
  }

  implicit class TemplateRequestBlockContextUpdaterOps(val blockContext: TemplateRequestBlockContext) extends AnyVal {
    def withTemplates(templates: Set[Template]): TemplateRequestBlockContext = {
      TemplateRequestBlockContextUpdater.withTemplates(blockContext, templates)
    }
  }

  implicit class IndicesFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def indices: Set[IndexName] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case _: RepositoryRequestBlockContext => Set.empty
        case bc: SnapshotRequestBlockContext => bc.indices
        case bc: TemplateRequestBlockContext => bc.templates.flatMap(_.patterns.toSet)
        case bc: GeneralIndexRequestBlockContext => bc.indices
        case bc: MultiIndexRequestBlockContext =>
          bc.indexPacks
            .flatMap {
              case Indices.Found(indices) => indices.toList
              case Indices.NotFound => Nil
            }
            .toSet
      }
    }
  }

  implicit class RepositoriesFromBlockContext(val blockContext: BlockContext) extends AnyVal {
    def repositories: Set[RepositoryName] = {
      blockContext match {
        case _: CurrentUserMetadataRequestBlockContext => Set.empty
        case _: GeneralNonIndexRequestBlockContext => Set.empty
        case bc: RepositoryRequestBlockContext => bc.repositories
        case bc: SnapshotRequestBlockContext => bc.repositories
        case _: TemplateRequestBlockContext => Set.empty
        case _: GeneralIndexRequestBlockContext => Set.empty
        case _: MultiIndexRequestBlockContext => Set.empty
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
        case _: TemplateRequestBlockContext => Set.empty
        case _: GeneralIndexRequestBlockContext => Set.empty
        case _: MultiIndexRequestBlockContext => Set.empty
      }
    }
  }
}