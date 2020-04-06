package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseIndicesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                               esContext: EsContext,
                                                               clusterService: RorClusterService,
                                                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indicesOrWildcard(indicesFrom(actionRequest))
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(indices) =>
        update(actionRequest, indices)
      case None =>
        ShouldBeInterrupted
    }
  }

  protected def indicesFrom(request: R): Set[IndexName]

  protected def update(request: R, indices: NonEmptyList[IndexName]): ModificationResult

  private def indicesOrWildcard(indices: Set[IndexName]) = {
    if(indices.nonEmpty) indices else Set(IndexName.wildcard)
  }
}
