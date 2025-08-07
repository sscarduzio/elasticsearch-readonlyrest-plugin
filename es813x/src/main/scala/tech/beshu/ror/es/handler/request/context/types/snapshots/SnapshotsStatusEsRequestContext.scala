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
import monix.eval.Task
import org.elasticsearch.action.admin.cluster.snapshots.status.{SnapshotsStatusRequest, SnapshotsStatusResponse}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
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

import scala.jdk.CollectionConverters.*

class SnapshotsStatusEsRequestContext private(actionRequest: SnapshotsStatusRequest,
                                              allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]],
                                              esContext: EsContext,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[SnapshotsStatusRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: SnapshotsStatusRequest): Set[SnapshotName] =
    request
      .snapshots().asSafeSet
      .flatMap(SnapshotName.from)

  override protected def repositoriesFrom(request: SnapshotsStatusRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.repository())
      .getOrElse(throw RequestSeemsToBeInvalid[SnapshotsStatusRequest]("Repository name is empty"))
  }

  override protected def requestedIndicesFrom(request: SnapshotsStatusRequest): Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    if (isCurrentSnapshotStatusRequest(actionRequest)) updateSnapshotStatusResponse(blockContext)
    else modifySnapshotStatusRequest(actionRequest, blockContext)
  }

  private def updateSnapshotStatusResponse(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    ModificationResult.UpdateResponse.create {
      case r: SnapshotsStatusResponse => Task.delay(filterOutNotAllowedSnapshotsAndRepositories(r, blockContext))
      case r => Task.now(r)
    }
  }

  private def filterOutNotAllowedSnapshotsAndRepositories(response: SnapshotsStatusResponse,
                                                          blockContext: SnapshotRequestBlockContext): SnapshotsStatusResponse = {
    val allowedRepositoriesMatcher = PatternsMatcher.create(blockContext.repositories)
    val allowedSnapshotsMatcher = PatternsMatcher.create(blockContext.snapshots)

    val allowedSnapshotStatuses = response
      .getSnapshots.asSafeList
      .filter { snapshotStatus =>
        (for {
          repositoryName <- RepositoryName.from(snapshotStatus.getSnapshot.getRepository)
          snapshotName <- SnapshotName.from(snapshotStatus.getSnapshot.getSnapshotId.getName)
        } yield {
          allowedRepositoriesMatcher.`match`(repositoryName) &&
            allowedSnapshotsMatcher.`match`(snapshotName)
        }) getOrElse false
      }

    on(response).set("snapshots", allowedSnapshotStatuses.asJava)
    response
  }

  private def modifySnapshotStatusRequest(request: SnapshotsStatusRequest,
                                          blockContext: SnapshotRequestBlockContext) = {
    val updateResult = for {
      repository <- repositoryFrom(blockContext)
      snapshots <- snapshotsFrom(blockContext)
    } yield update(request, repository, snapshots)
    updateResult match {
      case Right(_) =>
        ModificationResult.Modified
      case Left(_) =>
        logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.show} request. It's safer to forbid the request, but it looks like an issue. Please, report it as soon as possible.")
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def repositoryFrom(blockContext: SnapshotRequestBlockContext): Either[Unit, RepositoryName] = {
    val repositories = blockContext.repositories
    if (allRepositoriesRequested(repositories)) {
      Right(RepositoryName.All)
    } else {
      fullNamedRepositoriesFrom(repositories).toList match {
        case Nil =>
          Left(())
        case repository :: rest =>
          if (rest.nonEmpty) {
            logger.warn(s"[${blockContext.requestContext.id.show}] Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.show}]")
          }
          Right(repository)
      }
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext): Either[Unit, NonEmptyList[SnapshotName]] = {
    val snapshots = blockContext.snapshots
    if (allSnapshotsRequested(snapshots)) {
      Right(NonEmptyList.one(SnapshotName.All))
    } else {
      NonEmptyList.fromList(fullNamedSnapshotsFrom(snapshots).toList) match {
        case Some(list) => Right(list)
        case None => Left(())
      }
    }
  }

  private def fullNamedRepositoriesFrom(repositories: Iterable[RepositoryName]): Set[RepositoryName.Full] = {
    val allFullNameRepositories = allSnapshots.keys
    PatternsMatcher
      .create(repositories)
      .filter(allFullNameRepositories)
  }

  private def fullNamedSnapshotsFrom(snapshots: Iterable[SnapshotName]): Set[SnapshotName.Full] = {
    val allFullNameSnapshots: Set[SnapshotName.Full] = allSnapshots.values.toCovariantSet.flatten
    PatternsMatcher
      .create(snapshots)
      .filter(allFullNameSnapshots)
  }

  private def allSnapshotsRequested(requestedSnapshots: Iterable[SnapshotName]) =
    requestedSnapshots.exists(_ == SnapshotName.all)

  private def allRepositoriesRequested(requestedRepositories: Iterable[RepositoryName]) =
    requestedRepositories.exists(_ == RepositoryName.all)

  private def update(actionRequest: SnapshotsStatusRequest,
                     repository: RepositoryName,
                     snapshots: NonEmptyList[SnapshotName]) = {
    actionRequest.repository(RepositoryName.toString(repository))
    updateSnapshots(actionRequest, snapshots)
  }

  private def updateSnapshots(actionRequest: SnapshotsStatusRequest, snapshots: NonEmptyList[SnapshotName]) = {
    if (allSnapshotsRequested(snapshots.toList)) {
      actionRequest.snapshots(List.empty.toArray)
    } else {
      actionRequest.snapshots(snapshots.toList.map(SnapshotName.toString).toArray)
    }
  }

  private def isCurrentSnapshotStatusRequest(actionRequest: SnapshotsStatusRequest) = {
    val repositories = repositoriesFrom(actionRequest)
    val snapshots = snapshotsFrom(actionRequest)
    (repositories.isEmpty || repositories.contains(RepositoryName.all)) &&
      (snapshots.isEmpty || snapshots.contains(SnapshotName.all))
  }
}
object SnapshotsStatusEsRequestContext {

  def create(actionRequest: SnapshotsStatusRequest,
             esContext: EsContext,
             clusterService: RorClusterService,
             threadPool: ThreadPool): Task[SnapshotsStatusEsRequestContext] = {
    clusterService.allSnapshots
      .map { case (repository, getSnapshots) => getSnapshots.map((repository, _)) }
      .toList.sequence
      .map { allSnapshots =>
        new SnapshotsStatusEsRequestContext(actionRequest, allSnapshots.toMap, esContext, clusterService, threadPool)
      }
  }
}