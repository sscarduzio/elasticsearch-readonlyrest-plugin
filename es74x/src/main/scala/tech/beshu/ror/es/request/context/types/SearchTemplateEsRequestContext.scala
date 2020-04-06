package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

class SearchTemplateEsRequestContext private(actionRequest: ActionRequest,
                                             esContext: EsContext,
                                             clusterService: RorClusterService,
                                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = {
    Option(invokeMethodCached(request, request.getClass, "getRequest"))
      .map(_.asInstanceOf[SearchRequest].indices.asSafeSet)
      .getOrElse(Set.empty)
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: ActionRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    Option(invokeMethodCached(actionRequest, actionRequest.getClass, "getRequest")) match {
      case Some(request: SearchRequest) =>
        request.indices(indices.toList.map(_.value.value): _*)
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