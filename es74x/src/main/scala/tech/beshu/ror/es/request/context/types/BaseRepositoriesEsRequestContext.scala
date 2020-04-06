package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RepositoryRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseRepositoriesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                    esContext: EsContext,
                                                                    clusterService: RorClusterService,
                                                                    override val threadPool: ThreadPool)
  extends BaseEsRequestContext[RepositoryRequestBlockContext](esContext, clusterService)
    with EsRequest[RepositoryRequestBlockContext] {

  override val initialBlockContext: RepositoryRequestBlockContext = RepositoryRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    repositoriesFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: RepositoryRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.repositories.toList) match {
      case Some(repositories) =>
        update(actionRequest, repositories)
      case None =>
        ShouldBeInterrupted
    }
  }

  protected def repositoriesFrom(request: R): Set[RepositoryName]

  protected def update(request: R, repositories: NonEmptyList[RepositoryName]): ModificationResult
}

