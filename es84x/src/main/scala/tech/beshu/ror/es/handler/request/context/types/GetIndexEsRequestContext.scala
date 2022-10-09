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
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.utils.FilterableAliasesMap._
import tech.beshu.ror.utils.ScalaOps._

import scala.language.postfixOps

class GetIndexEsRequestContext(actionRequest: GetIndexRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[GetIndexRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: GetIndexRequest): Set[ClusterIndexName] = {
    request
      .indices().asSafeList
      .flatMap(ClusterIndexName.fromString)
      .toSet
  }

  override protected def update(request: GetIndexRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    request.indices(filteredIndices.map(_.stringify).toList: _*)
    ModificationResult.UpdateResponse(filterAliases(_, allAllowedIndices))
  }

  private def filterAliases(response: ActionResponse,
                            allAllowedAliases: NonEmptyList[ClusterIndexName]): Task[ActionResponse] = {
    response match {
      case getIndexResponse: GetIndexResponse =>
        Task.now(new GetIndexResponse(
          getIndexResponse.indices(),
          getIndexResponse.mappings(),
          getIndexResponse.aliases().filterOutNotAllowedAliases(allowedAliases = allAllowedAliases),
          getIndexResponse.settings(),
          getIndexResponse.defaultSettings(),
          getIndexResponse.dataStreams()
        ))
      case other =>
        logger.error(s"${id.show} Unexpected response type - expected: [${classOf[GetIndexResponse].getSimpleName}], was: [${other.getClass.getSimpleName}]")
        Task.now(new GetIndexResponse(
          Array.empty,
          Map.asEmptyJavaMap,
          Map.asEmptyJavaMap,
          Map.asEmptyJavaMap,
          Map.asEmptyJavaMap,
          Map.asEmptyJavaMap
        ))
    }
  }
}
