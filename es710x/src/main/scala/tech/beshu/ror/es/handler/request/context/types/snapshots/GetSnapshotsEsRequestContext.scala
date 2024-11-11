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
import monix.eval.Task
import org.elasticsearch.action.admin.cluster.snapshots.get.{GetSnapshotsRequest, GetSnapshotsResponse}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, RequestedIndex, SnapshotName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
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

class GetSnapshotsEsRequestContext(actionRequest: GetSnapshotsRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[GetSnapshotsRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: GetSnapshotsRequest): Set[SnapshotName] = {
    request
      .snapshots().asSafeSet
      .flatMap(SnapshotName.from)
  }

  override protected def repositoriesFrom(request: GetSnapshotsRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[GetSnapshotsRequest]("Repository name is empty"))
  }

  override protected def requestedIndicesFrom(request: GetSnapshotsRequest): Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotsFrom(blockContext)
      repository <- repositoryFrom(blockContext)
    } yield update(actionRequest, snapshots, repository)
    updateResult match {
      case Right(_) =>
        ModificationResult.UpdateResponse {
          case r: GetSnapshotsResponse =>
            Task.delay(updateGetSnapshotResponse(r, blockContext.allAllowedIndices))
          case r =>
            Task.now(r)
        }
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.show} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.from(blockContext.snapshots) match {
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

  private def update(actionRequest: GetSnapshotsRequest,
                     snapshots: UniqueNonEmptyList[SnapshotName],
                     repository: RepositoryName) = {
    actionRequest.snapshots(snapshots.toList.map(SnapshotName.toString).toArray)
    actionRequest.repository(RepositoryName.toString(repository))
  }

  private def updateGetSnapshotResponse(response: GetSnapshotsResponse,
                                        allAllowedIndices: Set[ClusterIndexName]): GetSnapshotsResponse = {
    val matcher = PatternsMatcher.create(allAllowedIndices)
    response
      .getSnapshots.asSafeList
      .foreach { snapshot =>
        val snapshotIndices = snapshot.indices().asSafeList.flatMap(ClusterIndexName.fromString)
        val filteredSnapshotIndices = matcher.filter(snapshotIndices)
        on(snapshot).set("indices", filteredSnapshotIndices.stringify.asJava)
        snapshot
      }
    response
  }
}
