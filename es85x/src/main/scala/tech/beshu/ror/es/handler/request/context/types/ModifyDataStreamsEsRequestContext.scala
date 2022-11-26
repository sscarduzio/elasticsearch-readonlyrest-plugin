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
import org.elasticsearch.action.datastreams.ModifyDataStreamsAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class ModifyDataStreamsEsRequestContext(actionRequest: ModifyDataStreamsAction.Request,
                                        esContext: EsContext,
                                        aclContext: AccessControlStaticContext,
                                        clusterService: RorClusterService,
                                        override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val originIndices = getIndicesFrom(actionRequest).toSet

  override def indicesFrom(request: ModifyDataStreamsAction.Request): Set[domain.ClusterIndexName] = {
    getIndicesFrom(request).toSet
  }

  override def update(request: ModifyDataStreamsAction.Request,
                      filteredIndices: NonEmptyList[ClusterIndexName],
                      allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    logger.error(s"[${id.show}] This request ${request.getClass.getCanonicalName} cannot be handled by the ROR ACL, " +
      s"so it's forbidden for security reasons. Please report the issue.")
    ModificationResult.ShouldBeInterrupted
  }

  private def getIndicesFrom(request: ModifyDataStreamsAction.Request) = {
    request.indices().asSafeList.flatMap(ClusterIndexName.fromString)
  }
}
