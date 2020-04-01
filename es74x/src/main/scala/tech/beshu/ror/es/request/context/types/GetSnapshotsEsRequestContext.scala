package tech.beshu.ror.es.request.context.types

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest
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
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GetSnapshotsEsRequestContext(actionRequest: GetSnapshotsRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseSnapshotEsRequestContext[GetSnapshotsRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def snapshotsFromRequest: Set[SnapshotName] = {
    actionRequest
      .snapshots().asSafeList
      .flatMap { s =>
        NonEmptyString.unapply(s).map(SnapshotName.apply)
      }
      .toSet[SnapshotName]
  }

  override protected def repositoriesFromRequest: Set[RepositoryName] = Set {
    NonEmptyString
      .from(actionRequest.repository())
      .map(RepositoryName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[CreateSnapshotRequest](msg),
        identity
      )
  }

  override protected def indicesFromRequest: Set[domain.IndexName] = Set(IndexName.wildcard)

  override protected def modifyRequest(blockContext: BlockContext.SnapshotRequestBlockContext): ModificationResult = {
    val updateResult = for {
      snapshots <- snapshotsFrom(blockContext)
      repository <- repositoryFrom(blockContext)
    } yield update(actionRequest, snapshots, repository)
    updateResult match {
      case Right(_) => ModificationResult.Modified
      case Left(_) => ModificationResult.ShouldBeInterrupted
    }
  }

  private def snapshotsFrom(blockContext: SnapshotRequestBlockContext) = {
    UniqueNonEmptyList.fromList(blockContext.snapshots.toList) match {
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
          logger.warn(s"[${blockContext.requestContext.id.show}] Filter result contains more than one repository. First was taken. Whole set of repositories [${repositories.mkString(",")}]")
        }
        Right(repository)
    }
  }

  private def update(actionRequest: GetSnapshotsRequest,
                     snapshots: UniqueNonEmptyList[SnapshotName],
                     repository: RepositoryName) = {
    actionRequest.snapshots(snapshots.toList.map(_.value.value).toArray)
    actionRequest.repository(repository.value.value)
  }
}
