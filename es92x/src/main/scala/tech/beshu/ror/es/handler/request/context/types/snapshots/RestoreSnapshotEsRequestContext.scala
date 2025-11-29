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
package tech.beshu.ror.es.handler.request.context.types.snapshots

import monix.eval.Task
import cats.data.NonEmptyList
import cats.implicits.*
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.RequestedIndex.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, RequestedIndex, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseSnapshotEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class RestoreSnapshotEsRequestContext private(actionRequest: RestoreSnapshotRequest,
                                              requestedRepository: RepositoryName.Full,
                                              requestedSnapshot: SnapshotName.Full,
                                              requestedIndices: Set[RequestedIndex[ClusterIndexName]],
                                              esContext: EsContext,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[RestoreSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: RestoreSnapshotRequest): Set[SnapshotName] =
    Set(requestedSnapshot)

  override protected def repositoriesFrom(request: RestoreSnapshotRequest): Set[RepositoryName] =
    Set(requestedRepository)

  override protected def requestedIndicesFrom(request: RestoreSnapshotRequest): Set[RequestedIndex[ClusterIndexName]] =
    requestedIndices

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- allowedSnapshotFrom(blockContext)
      repository <- allowedRepositoryFrom(blockContext)
      indices <- allowedIndicesFrom(blockContext)
    } yield update(actionRequest, snapshots, repository, indices)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"Cannot update ${actionRequest.getClass.show} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def allowedSnapshotFrom(blockContext: SnapshotRequestBlockContext) = {
    val snapshots = blockContext.snapshots.toList
    snapshots match {
      case Nil =>
        Left(())
      case snapshot :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"Filtered result contains more than one snapshot. First was taken. The whole set of repositories [${snapshots.show}]")
        }
        Right(snapshot)
    }
  }

  private def allowedRepositoryFrom(blockContext: SnapshotRequestBlockContext) = {
    val repositories = blockContext.repositories.toList
    repositories match {
      case Nil =>
        Left(())
      case repository :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.show}]")
        }
        Right(repository)
    }
  }

  private def allowedIndicesFrom(blockContext: SnapshotRequestBlockContext) = {
    NonEmptyList.fromList(blockContext.filteredIndices.toList) match {
      case None => Left(())
      case Some(indices) => Right(indices)
    }
  }

  private def update(request: RestoreSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: NonEmptyList[RequestedIndex[ClusterIndexName]]) = {
    request.snapshot(SnapshotName.toString(snapshot))
    request.repository(RepositoryName.toString(repository))
    request.indices(indices.stringify: _*)
  }
}
object RestoreSnapshotEsRequestContext {

  def create(actionRequest: RestoreSnapshotRequest,
             esContext: EsContext,
             clusterService: RorClusterService,
             threadPool: ThreadPool): Task[RestoreSnapshotEsRequestContext] = {
    for {
      requestedRepository <- Task(repositoryFrom(actionRequest))
      requestedSnapshot <- Task(snapshotFrom(actionRequest))
      requestedIndices <- requestedIndicesFrom(actionRequest, requestedRepository, requestedSnapshot, clusterService)
    } yield RestoreSnapshotEsRequestContext(
      actionRequest,
      requestedRepository,
      requestedSnapshot,
      requestedIndices,
      esContext,
      clusterService,
      threadPool
    )
  }

  private def repositoryFrom(request: RestoreSnapshotRequest): RepositoryName.Full = {
    RepositoryName
      .from(request.repository())
      .map {
        case repository@RepositoryName.Full(_) => repository
        case RepositoryName.Pattern(_) | RepositoryName.All | RepositoryName.Wildcard =>
          throw RequestSeemsToBeInvalid[RestoreSnapshotRequest]("Repository name cannot contain wildcard")
      }
      .getOrElse(throw RequestSeemsToBeInvalid[RestoreSnapshotRequest]("Repository name is empty"))
  }

  private def snapshotFrom(request: RestoreSnapshotRequest): SnapshotName.Full = {
    SnapshotName
      .from(request.snapshot())
      .map {
        case snapshot@SnapshotName.Full(_) => snapshot
        case SnapshotName.Pattern(_) | SnapshotName.All | SnapshotName.Wildcard =>
          throw RequestSeemsToBeInvalid[RestoreSnapshotRequest]("Snapshot name cannot contain wildcard")
      }
      .getOrElse(throw RequestSeemsToBeInvalid[RestoreSnapshotRequest]("Snapshot name is empty"))
  }

  private def requestedIndicesFrom(request: RestoreSnapshotRequest,
                                   repository: RepositoryName.Full,
                                   snapshot: SnapshotName.Full,
                                   clusterService: RorClusterService) = {
    clusterService
      .snapshotIndices(repository, snapshot)
      .map(_.filterBy(indicesFrom(request)))
      .map(_.orWildcardWhenEmpty)
  }

  private def indicesFrom(request: RestoreSnapshotRequest) = {
    request
      .indices.asSafeSet
      .flatMap(RequestedIndex.fromString)
      .orWildcardWhenEmpty
  }
}
