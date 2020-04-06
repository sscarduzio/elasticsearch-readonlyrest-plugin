package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class DummyCompositeIndicesEsRequestContext(actionRequest: ActionRequest with CompositeIndicesRequest,
                                            esContext: EsContext,
                                            clusterService: RorClusterService,
                                            override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest with CompositeIndicesRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[domain.IndexName] = Set.empty

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                indices: NonEmptyList[domain.IndexName]): ModificationResult = Modified
}