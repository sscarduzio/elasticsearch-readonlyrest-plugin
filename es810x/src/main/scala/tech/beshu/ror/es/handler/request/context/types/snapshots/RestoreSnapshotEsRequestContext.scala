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

import cats.data.NonEmptyList
import cats.implicits.*
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseSnapshotEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class RestoreSnapshotEsRequestContext(actionRequest: RestoreSnapshotRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[RestoreSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: RestoreSnapshotRequest): Set[SnapshotName] =
    SnapshotName
      .from(request.snapshot())
      .toCovariantSet

  override protected def repositoriesFrom(request: RestoreSnapshotRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[RestoreSnapshotRequest]("Repository name is empty"))
  }

  override protected def indicesFrom(request: RestoreSnapshotRequest): Set[ClusterIndexName] =
    request
      .indices.asSafeSet
      .flatMap(ClusterIndexName.fromString)
      .orWildcardWhenEmpty

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
      indices <- indicesFrom(blockContext)
    } yield update(actionRequest, snapshots, repository, indices)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.show} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotFrom(blockContext: SnapshotRequestBlockContext) = {
    val snapshots = blockContext.snapshots.toList
    snapshots match {
      case Nil =>
        Left(())
      case snapshot :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one snapshot. First was taken. The whole set of repositories [${snapshots.show}]")
        }
        Right(snapshot)
    }
  }

  private def repositoryFrom(blockContext: SnapshotRequestBlockContext) = {
    val repositories = blockContext.repositories.toList
    repositories match {
      case Nil =>
        Left(())
      case repository :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.show}]")
        }
        Right(repository)
    }
  }

  private def indicesFrom(blockContext: SnapshotRequestBlockContext) = {
    NonEmptyList.fromList(blockContext.filteredIndices.toList) match {
      case None => Left(())
      case Some(indices) => Right(indices)
    }
  }

  private def update(request: RestoreSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: NonEmptyList[ClusterIndexName]) = {
    request.snapshot(SnapshotName.toString(snapshot))
    request.repository(RepositoryName.toString(repository))
    request.indices(indices.toIterable.sortByNameWithExcludingIndicesAtTheEnd().stringify: _*)
  }
}
