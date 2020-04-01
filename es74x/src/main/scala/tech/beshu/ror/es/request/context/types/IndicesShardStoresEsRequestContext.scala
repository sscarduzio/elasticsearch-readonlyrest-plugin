package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

class IndicesShardStoresEsRequestContext(actionRequest: IndicesShardStoresRequest,
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
    indicesFromRequest
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    actionRequest.indices(blockContext.indices.toList.map(_.value.value): _*)
    Modified
  }

  private def indicesFromRequest = {
    val indices = actionRequest.indices.asSafeSet.flatMap(IndexName.fromString)
    if (indices.isEmpty) Set(IndexName.wildcard) else indices
  }
}
