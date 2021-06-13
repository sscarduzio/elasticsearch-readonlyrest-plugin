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
import org.elasticsearch.action.admin.indices.shrink.ShrinkRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

import scala.collection.JavaConverters._

class ShrinkEsRequestContext(actionRequest: ShrinkRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ShrinkRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ShrinkRequest): Set[ClusterIndexName] = {
    (request.getSourceIndex :: request.getShrinkIndexRequest.index() :: request.getShrinkIndexRequest.aliases().asScala.map(_.name()).toList)
      .flatMap(ClusterIndexName.fromString)
      .toSet
  }

  override protected def update(request: ShrinkRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    val notAllowedIndices = indicesFrom(actionRequest) -- filteredIndices.toList.toSet
    if (notAllowedIndices.isEmpty) {
      Modified
    } else {
      throw new IllegalStateException(s"Resize request is write request and such requests need all indices to be allowed. Not allowed indices=[${notAllowedIndices.map(_.show).mkString(",")}]")
    }
  }
}