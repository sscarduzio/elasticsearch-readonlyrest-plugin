package tech.beshu.ror.es.request.context.types


import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.rradmin.RRAdminRequest

class CurrentUserMetadataEsRequestContext(actionRequest: RRAdminRequest,
                                          esContext: EsContext,
                                          clusterService: RorClusterService,
                                          override val threadPool: ThreadPool)
  extends BaseEsRequestContext[CurrentUserMetadataRequestBlockContext](esContext, clusterService)
    with EsRequest[CurrentUserMetadataRequestBlockContext] {

  override val initialBlockContext: CurrentUserMetadataRequestBlockContext = CurrentUserMetadataRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty
  )

  override protected def modifyRequest(blockContext: CurrentUserMetadataRequestBlockContext): ModificationResult = Modified
}
