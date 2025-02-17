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
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
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
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.jdk.CollectionConverters.*

class CreateSnapshotEsRequestContext(actionRequest: CreateSnapshotRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[CreateSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override def snapshotsFrom(request: CreateSnapshotRequest): Set[SnapshotName] = Set {
    SnapshotName
      .from(request.snapshot())
      .getOrElse(throw RequestSeemsToBeInvalid[CreateSnapshotRequest]("Snapshot name is empty"))
  }

  override protected def repositoriesFrom(request: CreateSnapshotRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[CreateSnapshotRequest]("Repository name is empty"))
  }

  override protected def requestedIndicesFrom(request: CreateSnapshotRequest): Set[RequestedIndex[ClusterIndexName]] = {
    request
      .indices().asSafeSet
      .flatMap(RequestedIndex.fromString)
      .orWildcardWhenEmpty
  }

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshot <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
      indices <- filteredIndicesFrom(blockContext)
    } yield update(actionRequest, snapshot, repository, indices)
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
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one snapshot. First was taken. The whole set of snapshots [${snapshots.show}]")
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

  private def filteredIndicesFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.from(blockContext.filteredIndices) match {
      case Some(value) => Right(value)
      case None => Left(())
    }
  }

  private def update(actionRequest: CreateSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: UniqueNonEmptyList[RequestedIndex[ClusterIndexName]]) = {
    actionRequest.snapshot(SnapshotName.toString(snapshot))
    actionRequest.repository(RepositoryName.toString(repository))
    actionRequest.indices(indices.stringify.asJava)
    // todo: is it ok?
  }
}
