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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class IndicesShardStoresEsRequestContext(actionRequest: IndicesShardStoresRequest,
                                         esContext: EsContext,
                                         aclContext: AccessControlStaticContext,
                                         override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[IndicesShardStoresRequest](actionRequest, esContext, aclContext, threadPool) {

  override protected def requestedIndicesFrom(request: IndicesShardStoresRequest): Set[RequestedIndex[ClusterIndexName]] = {
    request.indices.asSafeSet.flatMap(RequestedIndex.fromString)
  }

  override protected def update(request: IndicesShardStoresRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    actionRequest.indices(filteredIndices.stringify: _*)
    Modified
  }
}
