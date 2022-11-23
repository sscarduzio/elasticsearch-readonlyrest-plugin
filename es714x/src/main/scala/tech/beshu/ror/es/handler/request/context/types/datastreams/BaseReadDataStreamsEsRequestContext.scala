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
package tech.beshu.ror.es.handler.request.context.types.datastreams

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.request.context.types.BaseIndicesEsRequestContext
import tech.beshu.ror.utils.ReflecUtils

import scala.collection.JavaConverters._

private[datastreams] abstract class BaseReadDataStreamsEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                                            indices: Set[ClusterIndexName],
                                                                                            esContext: EsContext,
                                                                                            aclContext: AccessControlStaticContext,
                                                                                            clusterService: RorClusterService,
                                                                                            override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[R](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override def indicesFrom(request: R): Set[ClusterIndexName] = indices

  override def update(request: R,
                      filteredIndices: NonEmptyList[ClusterIndexName],
                      allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    if (tryUpdate(actionRequest, filteredIndices)) Modified
    else {
      logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getCanonicalName} request. We're using reflection to modify the request data streams and it fails. Please, report the issue.")
      ShouldBeInterrupted
    }
  }

  private def tryUpdate(actionRequest: R, indices: NonEmptyList[ClusterIndexName]) = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet(indicesMethodName),
      indices.toList.map(_.stringify).toSet.asJava
    )
  }

  protected def indicesMethodName: String
}
