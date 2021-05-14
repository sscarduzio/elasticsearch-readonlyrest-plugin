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
package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.status.SnapshotsStatusRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

// curl -vk -u admin:container "http://localhost:9201/_snapshot/dev2-repo-1,dev2-repo-2/dev2-snap-1,dev1-snap-1/_status?pretty"
// doesn't work.
// repository = full name, only one
// snapshot = full name, more than one
class SnapshotsStatusEsRequestContext(actionRequest: SnapshotsStatusRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[SnapshotsStatusRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: SnapshotsStatusRequest): Set[SnapshotName] =
    request
      .snapshots().asSafeList
      .flatMap(SnapshotName.from)
      .toSet[SnapshotName]

  override protected def repositoriesFrom(request: SnapshotsStatusRequest): Set[RepositoryName] = Set {
    NonEmptyString
      .from(request.repository())
      .map(RepositoryName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[CreateSnapshotRequest](msg),
        identity
      )
  }

  override protected def indicesFrom(request: SnapshotsStatusRequest): Set[IndexName] =
    Set(IndexName.wildcard)

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotsFrom(blockContext)
      repository <- repositoryFrom(blockContext)
    } yield update(actionRequest, snapshots, repository)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getSimpleName} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext) = {
    NonEmptyList.fromList(blockContext.snapshots.toList) match {
      case Some(list) => Right(list)
      case None => Left(())
    }
  }

  private def repositoryFrom(blockContext: SnapshotRequestBlockContext) = {
    val repositories = blockContext.repositories.toList
    repositories match {
      case Nil =>
        Left(())
      case repository :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.mkString(",")}]")
        }
        Right(repository)
    }
  }

  private def update(actionRequest: SnapshotsStatusRequest,
                     snapshots: NonEmptyList[SnapshotName],
                     repository: RepositoryName) = {
    if (snapshotsFrom(actionRequest).isEmpty && snapshots.contains_(SnapshotName.all)) {
      // if empty, it's /_snapshot/_status request
    } else {
      actionRequest.snapshots(snapshots.toList.map(SnapshotName.toString).toArray)
    }
    actionRequest.repository(repository.value.value)
  }
}
