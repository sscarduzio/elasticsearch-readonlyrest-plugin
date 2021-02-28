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
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateRequest, SimulateIndexTemplateResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

class SimulateIndexTemplateRequestEsRequestContext(actionRequest: SimulateIndexTemplateRequest,
                                                   esContext: EsContext,
                                                   aclContext: AccessControlStaticContext,
                                                   clusterService: RorClusterService,
                                                   override val threadPool: ThreadPool)
  extends BaseSimulateIndexTemplateEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: SimulateIndexTemplateRequest): Set[IndexName] =
    Option(request.getIndexName)
      .flatMap(NonEmptyString.unapply)
      .map(domain.IndexName.apply)
      .toSet

  override protected def update(request: SimulateIndexTemplateRequest,
                                filteredIndices: NonEmptyList[IndexName],
                                allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    if (filteredIndices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. The whole set of indices [${filteredIndices.toList.mkString(",")}]")
    }
    update(request, filteredIndices.head, allAllowedIndices)
  }

  private def update(request: SimulateIndexTemplateRequest,
                     index: IndexName,
                     allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.indexName(index.value.value)
    ModificationResult.UpdateResponse {
      case response: SimulateIndexTemplateResponse =>
        Task.now(filterAliasesAndIndexPatternsIn(response, allAllowedIndices))
      case other =>
        Task.now(other)
    }
  }
}