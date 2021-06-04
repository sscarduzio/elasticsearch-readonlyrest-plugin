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
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

import scala.collection.JavaConverters._

class ShrinkEsRequestContext(actionRequest: ShrinkRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ShrinkRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ShrinkRequest): Set[IndexName] = {
    (request.getSourceIndex :: request.getShrinkIndexRequest.index() :: request.getShrinkIndexRequest.aliases().asScala.map(_.name()).toList)
      .flatMap(IndexName.fromString)
      .toSet
  }

  override protected def update(request: ShrinkRequest, filteredIndices: NonEmptyList[IndexName], allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    val sourceIndex = IndexName.fromString(request.getSourceIndex)
    val targetIndex = IndexName.fromString(request.getShrinkIndexRequest.index())

    val isSourceIndexOnFilteredIndicesList = sourceIndex.exists(filteredIndices.toList.contains(_))
    val isTargetIndexOnFilteredIndicesList = targetIndex.exists(filteredIndices.toList.contains(_))

    if (isSourceIndexOnFilteredIndicesList && isTargetIndexOnFilteredIndicesList) {
      Modified
    } else {
      if (!isSourceIndexOnFilteredIndicesList) {
        logger.info(s"[${id.show}] Source index ShrinkRequest forbidden")
      }
      if (!isTargetIndexOnFilteredIndicesList) {
        logger.info(s"[${id.show}] Target index ShrinkRequest forbidden")
      }
      ShouldBeInterrupted
    }
  }
}
