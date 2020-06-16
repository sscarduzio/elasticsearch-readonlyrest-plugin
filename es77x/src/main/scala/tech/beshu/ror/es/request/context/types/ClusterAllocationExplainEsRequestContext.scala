package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.admin.cluster.allocation.ClusterAllocationExplainRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class ClusterAllocationExplainEsRequestContext(actionRequest: ClusterAllocationExplainRequest,
                                               esContext: EsContext,
                                               aclContext: AccessControlStaticContext,
                                               clusterService: RorClusterService,
                                               override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indexFrom(request: ClusterAllocationExplainRequest): IndexName = {
    IndexName
      .fromString(request.getIndex)
      .getOrElse {
        throw RequestSeemsToBeInvalid[ClusterAllocationExplainRequest]("Index name is invalid")
      }
  }

  override protected def update(request: ClusterAllocationExplainRequest, index: IndexName): ModificationResult = {
    request.setIndex(index.value.value)
    Modified
  }
}
