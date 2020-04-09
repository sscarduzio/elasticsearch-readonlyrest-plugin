package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.utils.ClusterServiceHelper._

class DeleteIndexTemplateEsRequestContext(actionRequest: DeleteIndexTemplateRequest,
                                          esContext: EsContext,
                                          clusterService: RorClusterService,
                                          override val threadPool: ThreadPool)
  extends BaseEsRequestContext[TemplateRequestBlockContext](esContext, clusterService)
    with EsRequest[TemplateRequestBlockContext] {

  override val initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indicesPatternsFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.indices.toList match {
      case Nil =>
        ShouldBeInterrupted
      case _ =>
        // no need to modify - just pass it through
        Modified
    }
  }

  private def indicesPatternsFrom(request: DeleteIndexTemplateRequest): Set[IndexName] = {
    getIndicesPatternsOfTemplate(clusterService, request.name())
  }

}