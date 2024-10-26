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

import cats.implicits._
import cats.data.NonEmptyList
import com.google.common.collect.Sets
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._

import scala.jdk.CollectionConverters._

class ReflectionBasedIndicesEsRequestContext private(actionRequest: ActionRequest,
                                                     indices: Set[ClusterIndexName],
                                                     esContext: EsContext,
                                                     aclContext: AccessControlStaticContext,
                                                     clusterService: RorClusterService,
                                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[ClusterIndexName] = indices

  override protected def update(request: ActionRequest,
                                filteredIndices: NonEmptyList[ClusterIndexName],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    if (tryUpdate(actionRequest, filteredIndices)) Modified
    else {
      logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getSimpleName} request. We're using reflection to modify the request indices and it fails. Please, report the issue.")
      ShouldBeInterrupted
    }
  }

  private def tryUpdate(actionRequest: ActionRequest, indices: NonEmptyList[ClusterIndexName]) = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet("index", "indices", "setIndex", "setIndices"),
      indices.toList.map(_.stringify).toSet.asJava
    )
  }
}

object ReflectionBasedIndicesEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[ReflectionBasedIndicesEsRequestContext] = {
    indicesFrom(arg.esContext.actionRequest)
      .map(new ReflectionBasedIndicesEsRequestContext(arg.esContext.actionRequest, _, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
  }

  private def indicesFrom(request: ActionRequest) = {
    getIndicesUsingReflection(request, methodName = "indices")
      .orElse(getIndicesUsingReflection(request, methodName = "getIndices"))
      .orElse(getIndicesUsingReflection(request, methodName = "index"))
      .orElse(getIndicesUsingReflection(request, methodName = "getIndex"))
      .map(indices => indices.toList.toSet.flatMap(ClusterIndexName.fromString))
  }

  private def getIndicesUsingReflection(request: ActionRequest, methodName: String) = {
    NonEmptyList.fromList(extractStringArrayFromPrivateMethod(methodName, request).asSafeList)
  }

}