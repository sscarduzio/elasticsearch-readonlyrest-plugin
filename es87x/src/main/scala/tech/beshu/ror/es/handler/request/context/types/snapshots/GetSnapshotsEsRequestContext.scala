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

import cats.implicits._
import monix.eval.Task
import org.elasticsearch.action.admin.cluster.snapshots.get.{GetSnapshotsRequest, GetSnapshotsResponse}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseSnapshotEsRequestContext
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.jdk.CollectionConverters._

class GetSnapshotsEsRequestContext(actionRequest: GetSnapshotsRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[GetSnapshotsRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: GetSnapshotsRequest): Set[SnapshotName] = {
    request
      .snapshots().asSafeList
      .flatMap(SnapshotName.from)
      .toSet[SnapshotName]
  }

  override protected def repositoriesFrom(request: GetSnapshotsRequest): Set[RepositoryName] = {
    request
      .repositories().asSafeList
      .flatMap(RepositoryName.from)
      .toSet
  }

  override protected def indicesFrom(request: GetSnapshotsRequest): Set[domain.ClusterIndexName] =
    Set(ClusterIndexName.Local.wildcard)

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotsFrom(blockContext)
      repository <- repositoriesFrom(blockContext)
    } yield update(actionRequest, snapshots, repository)
    updateResult match {
      case Right(_) =>
        ModificationResult.UpdateResponse {
          case r: GetSnapshotsResponse =>
            Task.now(updateGetSnapshotResponse(r, blockContext.allAllowedIndices))
          case r =>
            Task.now(r)
        }
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getSimpleName} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.fromIterable(blockContext.snapshots) match {
      case Some(list) => Right(list)
      case None => Left(())
    }
  }

  private def repositoriesFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.fromIterable(blockContext.repositories) match {
      case Some(list) => Right(list)
      case None => Left(())
    }
  }

  private def update(actionRequest: GetSnapshotsRequest,
                     snapshots: UniqueNonEmptyList[SnapshotName],
                     repositories: UniqueNonEmptyList[RepositoryName]) = {
    actionRequest.snapshots(snapshots.toList.map(SnapshotName.toString).toArray)
    actionRequest.repositories(repositories.toList.map(RepositoryName.toString).toArray: _*)
  }

  private def updateGetSnapshotResponse(response: GetSnapshotsResponse,
                                        allAllowedIndices: Set[ClusterIndexName]): GetSnapshotsResponse = {
    val matcher = PatternsMatcher.create(allAllowedIndices)
    response
      .getSnapshots.asSafeList
      .foreach { snapshot =>
        val snapshotIndices = snapshot.indices().asSafeList.flatMap(ClusterIndexName.fromString).toSet
        val filteredSnapshotIndices = matcher.filter(snapshotIndices).map(_.stringify).toList.asJava
        on(snapshot).set("indices", filteredSnapshotIndices)
        snapshot
      }
    response
  }
}
