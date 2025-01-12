package tech.beshu.ror.es.handler.request.context.types.xpacksecurity

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.Error.RequestCannotBeHandled
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.handler.request.context.ModificationResult.UpdateResponse
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.services.EsApiKeyService
import tech.beshu.ror.syntax.Set

import java.util.function.Supplier

class GetApiKeyEsRequestContext private[xpacksecurity](actionRequest: ActionRequest,
                                                       esContext: EsContext,
                                                       clusterService: RorClusterService,
                                                       esApiKeyService: EsApiKeyService,
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
    esApiKeyService.toString
    actionRequest.toString
    UpdateResponse.using { response =>
      // todo: filter api key by name
      response
    }
  }


}

object GetApiKeyEsRequestContext extends Logging {

  def unapply(arg: ReflectionBasedActionRequest): Option[GetApiKeyEsRequestContextCreator] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("GetApiKeyRequest")) { // todo:
      Some(new GetApiKeyEsRequestContextCreator(arg))
    } else {
      None
    }
  }
}

class GetApiKeyEsRequestContextCreator private[xpacksecurity](request: ReflectionBasedActionRequest) {

  def create(esApiKeyServiceSupplier: Supplier[Option[EsApiKeyService]]): Either[RequestCannotBeHandled, GetApiKeyEsRequestContext] = {
    esApiKeyServiceSupplier.get() match
      case Some(esApiKeyService) =>
        Right(new GetApiKeyEsRequestContext(request.esContext.actionRequest, request.esContext, request.clusterService, esApiKeyService, request.threadPool))
      case None =>
        Left(RequestCannotBeHandled("No ApiKeyService available"))
  }
}
