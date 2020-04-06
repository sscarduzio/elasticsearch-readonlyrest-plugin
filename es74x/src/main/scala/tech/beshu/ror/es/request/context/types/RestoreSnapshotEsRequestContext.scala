package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class RestoreSnapshotEsRequestContext(actionRequest: RestoreSnapshotRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[RestoreSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFrom(request: RestoreSnapshotRequest): Set[domain.SnapshotName] =
    NonEmptyString
      .from(request.snapshot())
      .map(SnapshotName.apply)
      .toOption.toSet

  override protected def repositoriesFrom(request: RestoreSnapshotRequest): Set[domain.RepositoryName] = Set {
    NonEmptyString
      .from(request.repository())
      .map(RepositoryName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[CreateSnapshotRequest](msg),
        identity
      )
  }

  override protected def indicesFrom(request: RestoreSnapshotRequest): Set[domain.IndexName] =
    request.indices.asSafeSet.flatMap(IndexName.fromString)

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
      indices <- indicesFrom(blockContext)
    } yield update(actionRequest, snapshots, repository, indices)
    updateResult match {
      case Right(_) => ModificationResult.Modified
      case Left(_) => ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotFrom(blockContext: SnapshotRequestBlockContext) = {
    val snapshots = blockContext.snapshots.toList
    snapshots match {
      case Nil =>
        Left(())
      case snapshot :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${blockContext.requestContext.id.show}] Filter result contains more than one snapshot. First was taken. Whole set of repositories [${snapshots.mkString(",")}]")
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
          logger.warn(s"[${blockContext.requestContext.id.show}] Filter result contains more than one repository. First was taken. Whole set of repositories [${repositories.mkString(",")}]")
        }
        Right(repository)
    }
  }

  private def indicesFrom(blockContext: SnapshotRequestBlockContext) = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case None => Left(())
      case Some(indices) => Right(indices)
    }
  }

  private def update(request: RestoreSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: NonEmptyList[IndexName]) = {
    request.snapshot(snapshot.value.value)
    request.repository(repository.value.value)
    request.indices(indices.toList.map(_.value.value): _*)
  }
}
