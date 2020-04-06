package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class IndicesStatsEsRequestContext(actionRequest: IndicesStatsRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[IndicesStatsRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: IndicesStatsRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: IndicesStatsRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    request.indices(indices.map(_.value.value).toList: _*)
    Modified
  }
}
