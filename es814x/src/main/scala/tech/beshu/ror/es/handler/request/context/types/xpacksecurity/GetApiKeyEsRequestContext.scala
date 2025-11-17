/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.handler.request.context.types.xpacksecurity

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.handler.AclAwareRequestFilter.Error.RequestCannotBeHandled
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.services.EsApiKeyService
import tech.beshu.ror.syntax.Set

import java.util.function.Supplier

class GetApiKeyEsRequestContext private[xpacksecurity](actionRequest: ActionRequest,
                                                       esContext: EsContext,
                                                       override implicit val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralNonIndexRequestBlockContext](esContext)
    with EsRequest[GeneralNonIndexRequestBlockContext] {

  override def initialBlockContext: GeneralNonIndexRequestBlockContext = GeneralNonIndexRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty
  )

  override protected def modifyRequest(blockContext: GeneralNonIndexRequestBlockContext): ModificationResult = {
    actionRequest.toString
//    UpdateResponse.using { response =>
//      // todo: filter api key by name
//      response
//    }
    ModificationResult.Modified
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
      case Some(_) =>
        Right(new GetApiKeyEsRequestContext(request.esContext.actionRequest, request.esContext, request.threadPool))
      case None =>
        Left(RequestCannotBeHandled("No ApiKeyService available"))
  }
}
