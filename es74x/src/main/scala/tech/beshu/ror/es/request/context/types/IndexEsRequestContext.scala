package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class IndexEsRequestContext(actionRequest: IndexRequest,
                            esContext: EsContext,
                            clusterService: RorClusterService,
                            override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def indexFrom(request: IndexRequest): IndexName = {
    IndexName
      .fromString(request.index())
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }

  override protected def update(request: IndexRequest, index: IndexName): ModificationResult = {
    request.index(index.value.value)
    Modified
  }
}
