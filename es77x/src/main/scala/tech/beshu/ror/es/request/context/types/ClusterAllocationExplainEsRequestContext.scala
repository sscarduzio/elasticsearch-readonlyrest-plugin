package tech.beshu.ror.es.request.context.types

import cats.implicits._
import cats.data.NonEmptyList
import org.elasticsearch.action.admin.cluster.allocation.ClusterAllocationExplainRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

class ClusterAllocationExplainEsRequestContext(actionRequest: ClusterAllocationExplainRequest,
                                               esContext: EsContext,
                                               aclContext: AccessControlStaticContext,
                                               clusterService: RorClusterService,
                                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterAllocationExplainRequest): Set[IndexName] = getIndexFrom(request).toSet

  override protected def update(request: ClusterAllocationExplainRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    getIndexFrom(request) match {
      case Some(indexName) =>
        updateIndexIn(request, indexName)
        Modified
      case None =>
        logger.error(s"[${id.show}] Cluster allocation explain request without index name is unavailable")
        ShouldBeInterrupted
    }
  }

  private def getIndexFrom(request: ClusterAllocationExplainRequest) = {
    Option(request.getIndex).flatMap(IndexName.fromString)
  }

  private def updateIndexIn(request: ClusterAllocationExplainRequest, indexName: IndexName) = {
    request.setIndex(indexName.value.value)
  }
}
