package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

class SearchTemplateEsRequestContext private(actionRequest: ActionRequest,
                                             esContext: EsContext,
                                             clusterService: RorClusterService,
                                             override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.empty,
    Set.empty,
    Set.empty,
    indicesFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(nelOfIndices) =>
        tryUpdate(actionRequest, nelOfIndices)
      case None =>
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: ActionRequest) = {
    Option(invokeMethodCached(request, request.getClass, "getRequest"))
      .map(_.asInstanceOf[SearchRequest].indices.asSafeSet)
      .getOrElse(Set.empty)
      .flatMap(IndexName.fromString)
  }

  private def tryUpdate(actionRequest: ActionRequest, nelOfIndices: NonEmptyList[IndexName]) = {
    Option(invokeMethodCached(actionRequest, actionRequest.getClass, "getRequest")) match {
      case Some(request: SearchRequest) =>
        request.indices(nelOfIndices.toList.map(_.value.value): _*)
        Modified
      case Some(_) | None =>
        ShouldBeInterrupted
    }
  }
}

object SearchTemplateEsRequestContext {
  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[SearchTemplateEsRequestContext] = {
    if (actionRequest.getClass.getSimpleName.startsWith("SearchTemplateRequest")) {
      Some(new SearchTemplateEsRequestContext(actionRequest, esContext, clusterService, threadPool))
    } else {
      None
    }
  }
}