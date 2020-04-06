package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class DeleteDocumentEsRequestContext(actionRequest: DeleteRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext[DeleteRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indexFrom(request: DeleteRequest): IndexName = {
    IndexName
      .fromString(actionRequest.index())
      .getOrElse {
        throw RequestSeemsToBeInvalid[DeleteRequest]("Invalid index name")
      }
  }

  override protected def update(actionRequest: DeleteRequest, index: IndexName): ModificationResult = {
    actionRequest.index(index.value.value)
    Modified
  }
}
