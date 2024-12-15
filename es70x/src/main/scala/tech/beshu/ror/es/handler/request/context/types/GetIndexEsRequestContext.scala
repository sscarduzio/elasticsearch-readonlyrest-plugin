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
import cats.implicits.*
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap.*
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils.ImmutableOpenMapOps
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class GetIndexEsRequestContext(actionRequest: GetIndexRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[GetIndexRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestedIndicesFrom(request: GetIndexRequest): Set[RequestedIndex[ClusterIndexName]] = {
    request
      .indices().asSafeSet
      .flatMap(RequestedIndex.fromString)
  }

  override protected def update(request: GetIndexRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    request.indices(filteredIndices.stringify: _*)
    ModificationResult.UpdateResponse(filterAliases(_, allAllowedIndices))
  }

  private def filterAliases(response: ActionResponse,
                            allAllowedAliases: NonEmptyList[ClusterIndexName]): Task[ActionResponse] = {
    response match {
      case getIndexResponse: GetIndexResponse =>
        Task.now(new GetIndexResponse(
          getIndexResponse.indices(),
          getIndexResponse.mappings(),
          getIndexResponse.aliases().filterOutNotAllowedAliases(allowedAliases = allAllowedAliases.toList),
          getIndexResponse.settings(),
          getIndexResponse.defaultSettings()
        ))
      case other =>
        logger.error(s"${id.show} Unexpected response type - expected: [${classOf[GetIndexResponse].show}], was: [${other.getClass.show}]")
        Task.now(new GetIndexResponse(
          Array.empty,
          ImmutableOpenMapOps.empty,
          ImmutableOpenMapOps.empty,
          ImmutableOpenMapOps.empty,
          ImmutableOpenMapOps.empty
        ))
    }
  }
}
