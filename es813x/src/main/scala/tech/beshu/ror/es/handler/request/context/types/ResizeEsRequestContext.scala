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
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified

import scala.jdk.CollectionConverters._

class ResizeEsRequestContext(actionRequest: ResizeRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ResizeRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ResizeRequest): Set[ClusterIndexName] = {
    (request.getSourceIndex :: request.getTargetIndexRequest.index() :: request.getTargetIndexRequest.aliases().asScala.map(_.name()).toList)
      .flatMap(ClusterIndexName.fromString)
      .toSet
  }

  override protected def update(request: ResizeRequest,
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
