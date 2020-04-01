package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

class DummyCompositeIndicesEsRequestContext(actionRequest: ActionRequest with CompositeIndicesRequest,
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
    Set.empty
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = Modified
}
