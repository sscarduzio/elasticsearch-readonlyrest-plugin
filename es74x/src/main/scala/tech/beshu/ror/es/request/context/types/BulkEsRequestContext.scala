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
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

import scala.collection.JavaConverters._

class BulkEsRequestContext(actionRequest: BulkRequest,
                           esContext: EsContext,
                           aclContext: AccessControlStaticContext,
                           clusterService: RorClusterService,
                           override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[BulkRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: BulkRequest): Set[IndexName] = {
    request.requests().asScala.map(_.index()).flatMap(IndexName.fromString).toSet
  }

  override protected def update(request: BulkRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    request.requests().removeIf { request: DocWriteRequest[_] => removeOrAlter(request, indices) }
    if(request.requests().asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(request: DocWriteRequest[_], filteredIndices: NonEmptyList[IndexName]) = {
    val expandedIndicesOfRequest = clusterService.expandIndices(IndexName.fromString(request.index()).toSet)
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices.toList.toSet).toList
    remaining match {
      case Nil =>
        true
      case one :: Nil =>
        request.index(one.value.value)
        false
      case one :: _ =>
        request.index(one.value.value)
        logger.warn(
          s"""[$taskId] One of requests from BulkOperation contains more than one index after expanding and intersect.
             |Picking first from [${remaining.mkString(",")}]"""".stripMargin
        )
        false
    }
  }
}
