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
package tech.beshu.ror.es.handler.request.context.types.ror

import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RorApiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

class RorApiEsRequestContext(actionRequest: RorActionRequest,
                             esContext: EsContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseEsRequestContext[RorApiRequestBlockContext](esContext, clusterService)
    with EsRequest[RorApiRequestBlockContext] {

  override val initialBlockContext: RorApiRequestBlockContext = RorApiRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty
  )

  override protected def modifyRequest(blockContext: RorApiRequestBlockContext): ModificationResult = {
    blockContext.userMetadata.loggedUser match {
      case Some(value) => actionRequest.setLoggedUser(value)
      case None =>
    }
    Modified
  }
}
