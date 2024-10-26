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
import cats.implicits._
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class ClusterStateEsRequestContext(actionRequest: ClusterStateRequest,
                                   esContext: EsContext,
                                   aclContext: AccessControlStaticContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ClusterStateRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterStateRequest): Set[ClusterIndexName] = {
    request.indices.asSafeSet.flatMap(ClusterIndexName.fromString)
  }

  override protected def update(request: ClusterStateRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    indicesFrom(request).toList match {
      case Nil if filteredIndices.exists(_ === ClusterIndexName.Local.wildcard) =>
        // hack: when empty indices list is replaced with wildcard index, returned result is wrong
        Modified
      case _ =>
        request.indices(filteredIndices.toList.map(_.stringify): _*)
        Modified
    }
  }
}
