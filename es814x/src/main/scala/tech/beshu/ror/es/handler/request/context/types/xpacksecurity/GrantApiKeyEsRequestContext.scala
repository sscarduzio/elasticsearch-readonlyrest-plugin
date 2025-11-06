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
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.Error.RequestCannotBeHandled
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.services.EsApiKeyService
import tech.beshu.ror.syntax.Set

import java.util.function.Supplier

class GrantApiKeyEsRequestContext private[xpacksecurity](actionRequest: ActionRequest,
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
//    esApiKeyService.toString
    actionRequest.toString
    val instance = ServiceAccountServiceRef.getInstance
    instance.toString
    Modified
  }
}

object GrantApiKeyEsRequestContext extends Logging {

  def unapply(arg: ReflectionBasedActionRequest): Option[GrantApiKeyEsRequestContextCreator] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("GrantApiKeyRequest")) {
      Some(new GrantApiKeyEsRequestContextCreator(arg))
    } else {
      None
    }
  }
}

class GrantApiKeyEsRequestContextCreator private[xpacksecurity](request: ReflectionBasedActionRequest) {

  def create(esApiKeyServiceSupplier: Supplier[Option[EsApiKeyService]]): Either[RequestCannotBeHandled, GrantApiKeyEsRequestContext] = {
    ServiceAccountServiceRef.getInstance match
      case Some(_) =>
        Right(new GrantApiKeyEsRequestContext(request.esContext.actionRequest, request.esContext, request.clusterService, request.threadPool))
      case None =>
        Left(RequestCannotBeHandled("No ApiKeyService available"))
  }
}
