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
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class IndicesAliasesEsRequestContext(actionRequest: IndicesAliasesRequest,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[IndicesAliasesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: IndicesAliasesRequest): Set[IndexName] = {
    request.getAliasActions.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
  }

  override protected def update(request: IndicesAliasesRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    // todo: fixme
//    request.getAliasActions.removeIf { action => removeOrAlter(action, indices.toList.toSet) }
    if (request.getAliasActions.asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(action: IndicesAliasesRequest.AliasActions,
                            filteredIndices: Set[IndexName]): Boolean = {
    val expandedIndicesOfRequest = clusterService.expandIndices(action.indices().asSafeSet.flatMap(IndexName.fromString))
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case indices =>
        action.indices(indices.map(_.value.value): _*)
        false
    }
  }
}
