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
package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class GetRollupIndexCapsEsRequestContext private(actionRequest: ActionRequest,
                                                 esContext: EsContext,
                                                 aclContext: AccessControlStaticContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = {
    val indicesName = on(request).call("indices").get[Array[String]]()
    indicesName.flatMap(IndexName.fromString).toSet
  }

  override protected def update(request: ActionRequest, filteredIndices: NonEmptyList[IndexName], allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    on(request).call("indices", filteredIndices.map(_.value.value).toList.toArray)
    Modified
  }
}

object GetRollupIndexCapsEsRequestContext {
  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           aclContext: AccessControlStaticContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[GetRollupIndexCapsEsRequestContext] = {
    if (actionRequest.getClass.getName.endsWith("GetRollupIndexCapsAction$Request")) {
      Some(new GetRollupIndexCapsEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool))
    } else {
      None
    }
  }
}