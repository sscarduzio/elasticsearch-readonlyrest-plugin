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
import com.google.common.collect.Sets
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class ReflectionBasedIndicesEsRequestContext private(actionRequest: ActionRequest,
                                                     indices: Set[IndexName],
                                                     esContext: EsContext,
                                                     aclContext: AccessControlStaticContext,
                                                     clusterService: RorClusterService,
                                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = indices

  override protected def update(request: ActionRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    if (tryUpdate(actionRequest, indices)) Modified
    else ShouldBeInterrupted
  }

  private def tryUpdate(actionRequest: ActionRequest, indices: NonEmptyList[IndexName]) = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet("index", "indices"),
      indices.toList.map(_.value.value).toSet.asJava
    )
  }
}

object ReflectionBasedIndicesEsRequestContext {

  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           aclContext: AccessControlStaticContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[ReflectionBasedIndicesEsRequestContext] = {
    indicesFrom(actionRequest)
      .map(new ReflectionBasedIndicesEsRequestContext(actionRequest, _, esContext, aclContext, clusterService, threadPool))
  }

  private def indicesFrom(request: ActionRequest) = {
    NonEmptyList
      .fromList(extractStringArrayFromPrivateMethod("indices", request).asSafeList)
      .orElse(NonEmptyList.fromList(extractStringArrayFromPrivateMethod("index", request).asSafeList))
      .map(indices => indices.toList.toSet.flatMap(IndexName.fromString))
  }
}