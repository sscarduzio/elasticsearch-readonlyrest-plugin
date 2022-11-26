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
package tech.beshu.ror.es.handler.request.context.types.datastreams

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.types.ReflectionBasedActionRequest
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.MatchResult

private[datastreams] class DataStreamsStatsEsRequestContext private(actionRequest: ActionRequest,
                                                                    indices: Set[ClusterIndexName],
                                                                    esContext: EsContext,
                                                                    aclContext: AccessControlStaticContext,
                                                                    clusterService: RorClusterService,
                                                                    override val threadPool: ThreadPool)
  extends BaseReadDataStreamsEsRequestContext(actionRequest, indices, esContext, aclContext, clusterService, threadPool) {

  override protected def setIndicesMethodName: String = "indices"
}

object DataStreamsStatsEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[DataStreamsStatsEsRequestContext] = {
    ReflectionBasedDataStreamsEsRequestContext
      .tryMatchActionRequest(
        actionRequest = arg.esContext.actionRequest,
        expectedClassCanonicalName = "org.elasticsearch.xpack.core.action.DataStreamsStatsAction.Request",
        getIndicesMethodName = "indices"
      ) match {
      case MatchResult.Matched(indices) =>
        Some(new DataStreamsStatsEsRequestContext(arg.esContext.actionRequest, indices, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
      case MatchResult.NotMatched =>
        None
    }
  }
}