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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.actions.rrmetadata.RRUserMetadataRequest
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.syntax.*

import scala.annotation.unused

class UserMetadataEsRequestContext(actionRequest: RRUserMetadataRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseEsRequestContext[UserMetadataRequestBlockContext](esContext, clusterService)
    with UserMetadataRequestContext
    with EsRequest[UserMetadataRequestBlockContext] {

  override val initialBlockContext: UserMetadataRequestBlockContext = UserMetadataRequestBlockContext(
    requestContext = this,
    blockMetadata = BlockMetadata.empty,
    responseHeaders = Set.empty,
    responseTransformations = List.empty
  )

  override def apiVersion: UserMetadataRequestContext.UserMetadataApiVersion = actionRequest.apiVersion

  override protected def modifyRequest(blockContext: UserMetadataRequestBlockContext): ModificationResult = Modified
}
