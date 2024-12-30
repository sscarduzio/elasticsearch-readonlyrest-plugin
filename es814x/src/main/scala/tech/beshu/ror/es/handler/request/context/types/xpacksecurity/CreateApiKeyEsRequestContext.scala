package tech.beshu.ror.es.handler.request.context.types.xpacksecurity

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.syntax.Set

class CreateApiKeyEsRequestContext private(actionRequest: ActionRequest,
                                          esContext: EsContext,
                                          clusterService: RorClusterService,
                                          override implicit val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralNonIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralNonIndexRequestBlockContext] {

  override def initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty
  )

  override protected def modifyRequest(blockContext: GeneralNonIndexRequestBlockContext): ModificationResult = {
    actionRequest.toString
    Modified
  }
}

object CreateApiKeyEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[CreateApiKeyEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("CreateApiKeyRequest")) {
      Some(new CreateApiKeyEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}
