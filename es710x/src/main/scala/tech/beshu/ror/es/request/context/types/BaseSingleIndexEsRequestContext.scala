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
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

abstract class BaseSingleIndexEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                   esContext: EsContext,
                                                                   aclContext: AccessControlStaticContext,
                                                                   clusterService: RorClusterService,
                                                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[R](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: R): Set[IndexName] = Set(indexFrom(request))

  override protected def update(request: R,
                                filteredIndices: NonEmptyList[IndexName],
                                allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    if (filteredIndices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. Whole set of indices [${filteredIndices.toList.mkString(",")}]")
    }
    update(request, filteredIndices.head)
  }


  protected def indexFrom(request: R): IndexName

  protected def update(request: R, index: IndexName): ModificationResult
}
