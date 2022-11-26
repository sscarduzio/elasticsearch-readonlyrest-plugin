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
import org.elasticsearch.action.datastreams.DataStreamsStatsAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class DataStreamsStatsEsRequestContext(actionRequest: DataStreamsStatsAction.Request,
                                       esContext: EsContext,
                                       aclContext: AccessControlStaticContext,
                                       clusterService: RorClusterService,
                                       override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override def indicesFrom(request: DataStreamsStatsAction.Request): Set[domain.ClusterIndexName] = {
    getIndexFrom(request).toSet
  }

  override def update(request: DataStreamsStatsAction.Request,
                      filteredIndices: NonEmptyList[ClusterIndexName],
                      allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    request.indices(filteredIndices.map(_.stringify).toList: _*)
    ModificationResult.Modified
  }

  private def getIndexFrom(request: DataStreamsStatsAction.Request) = {
    request.indices().asSafeList.flatMap(ClusterIndexName.fromString)
  }
}