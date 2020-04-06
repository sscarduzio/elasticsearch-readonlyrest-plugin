package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class SearchEsRequestContext(actionRequest: SearchRequest,
                             esContext: EsContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[SearchRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: SearchRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: SearchRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    optionallyDisableCaching()
    request.indices(indices.toList.map(_.value.value): _*)
    Modified
  }

  // Cache disabling for this request is crucial for document level security to work.
  // Otherwise we'd get an answer from the cache some times and would not be filtered
  private def optionallyDisableCaching(): Unit = {
    if(esContext.involveFilters) {
      logger.debug("ACL involves filters, will disable request cache for SearchRequest")
      actionRequest.requestCache(false)
    }
  }
}