package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class RolloverEsRequestContext(actionRequest: RolloverRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[RolloverRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: RolloverRequest): Set[IndexName] = {
    (Option(request.getNewIndexName).toSet ++ Set(request.getRolloverTarget))
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: RolloverRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    Modified
  }
}
