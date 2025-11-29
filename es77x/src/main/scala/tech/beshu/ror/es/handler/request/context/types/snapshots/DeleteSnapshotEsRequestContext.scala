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

import cats.implicits.*
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, RequestedIndex, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseSnapshotEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class DeleteSnapshotEsRequestContext(actionRequest: DeleteSnapshotRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[DeleteSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: DeleteSnapshotRequest): Set[SnapshotName] = Set {
    SnapshotName
      .from(request.snapshot())
      .getOrElse(throw RequestSeemsToBeInvalid[DeleteSnapshotRequest]("Snapshot name is empty"))
  }

  override protected def repositoriesFrom(request: DeleteSnapshotRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[DeleteSnapshotRequest]("Repository name is empty"))
  }

  override protected def requestedIndicesFrom(request: DeleteSnapshotRequest): Set[RequestedIndex[ClusterIndexName]] = Set.empty

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshot <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
    } yield update(actionRequest, snapshot, repository)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"Cannot update ${actionRequest.getClass.show} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotFrom(implicit blockContext: SnapshotRequestBlockContext) = {
    val snapshots = blockContext.snapshots.toList
    snapshots match {
      case Nil =>
        Left(())
      case snapshot :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"Filtered result contains more than one snapshot. First was taken. The whole set of snapshots [${snapshots.show}]")
        }
        Right(snapshot)
    }
  }

  private def repositoryFrom(implicit blockContext: SnapshotRequestBlockContext) = {
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

  private def update(actionRequest: DeleteSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName) = {
    actionRequest.snapshot(SnapshotName.toString(snapshot))
    actionRequest.repository(RepositoryName.toString(repository))
  }
}
