package tech.beshu.ror.es.request.context.types

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class CreateSnapshotEsRequestContext(actionRequest: CreateSnapshotRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[CreateSnapshotRequest](actionRequest, esContext, clusterService, threadPool) {

  override def snapshotsFrom(request: CreateSnapshotRequest): Set[SnapshotName] = Set {
    NonEmptyString
      .from(request.snapshot())
      .map(SnapshotName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[CreateSnapshotRequest](msg),
        identity
      )
  }

  override protected def repositoriesFrom(request: CreateSnapshotRequest): Set[RepositoryName] = Set {
    NonEmptyString
      .from(request.repository())
      .map(RepositoryName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[CreateSnapshotRequest](msg),
        identity
      )
  }

  override protected def indicesFrom(request: CreateSnapshotRequest): Set[IndexName] =
    Set(IndexName.wildcard)

  override protected def modifyRequest(blockContext: SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshot <- snapshotFrom(blockContext)
      repository <- repositoryFrom(blockContext)
      indices <- indicesFrom(blockContext)
    } yield update(actionRequest, snapshot, repository, indices)
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
          logger.warn(s"[${blockContext.requestContext.id.show}] Filter result contains more than one snapshot. First was taken. Whole set of snapshots [${snapshots.mkString(",")}]")
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
    UniqueNonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(value) => Right(value)
      case None => Left(())
    }
  }

  private def update(actionRequest: CreateSnapshotRequest,
                     snapshot: SnapshotName,
                     repository: RepositoryName,
                     indices: UniqueNonEmptyList[IndexName]) = {
    actionRequest.snapshot(snapshot.value.value)
    actionRequest.repository(repository.value.value)
    actionRequest.indices(indices.toList.map(_.value.value).asJava)
  }

}
