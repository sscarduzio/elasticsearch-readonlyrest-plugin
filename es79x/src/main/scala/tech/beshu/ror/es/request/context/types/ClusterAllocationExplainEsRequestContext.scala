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

import cats.implicits._
import cats.data.NonEmptyList
import org.elasticsearch.action.admin.cluster.allocation.ClusterAllocationExplainRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

class ClusterAllocationExplainEsRequestContext(actionRequest: ClusterAllocationExplainRequest,
                                               esContext: EsContext,
                                               aclContext: AccessControlStaticContext,
                                               clusterService: RorClusterService,
                                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterAllocationExplainRequest): Set[IndexName] =
    getIndexFrom(request).toSet

  override protected def update(request: ClusterAllocationExplainRequest,
                                filteredIndices: NonEmptyList[IndexName],
                                allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    getIndexFrom(request) match {
      case Some(_) =>
        if (filteredIndices.tail.nonEmpty) {
          logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. Whole set of indices [${filteredIndices.toList.mkString(",")}]")
        }
        updateIndexIn(request, filteredIndices.head)
        Modified
      case None if filteredIndices.exists(_ === IndexName.wildcard) =>
        Modified
      case None =>
        logger.error(s"[${id.show}] Cluster allocation explain request without index name is unavailable when block contains `indices` rule")
        ShouldBeInterrupted
    }
  }

  private def getIndexFrom(request: ClusterAllocationExplainRequest) = {
    Option(request.getIndex).flatMap(IndexName.fromString)
  }

  private def updateIndexIn(request: ClusterAllocationExplainRequest, indexName: IndexName) = {
    request.setIndex(indexName.value.value)
  }
}
