package tech.beshu.ror.es.request.context.types

import org.elasticsearch.threadpool.ThreadPool
import org.json.JSONObject
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.actions.rrauditevent.RRAuditEventRequest
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

class AuditEventESRequestContext(actionRequest: RRAuditEventRequest,
                                 esContext: EsContext,
                                 clusterService: RorClusterService,
                                 override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralNonIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralNonIndexRequestBlockContext] {

  override val initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty
  )

  override val additionalAuditFields: Map[String, JSONObject] = actionRequest.eventsJsons

  override protected def modifyRequest(blockContext: GeneralNonIndexRequestBlockContext): ModificationResult = Modified
}

