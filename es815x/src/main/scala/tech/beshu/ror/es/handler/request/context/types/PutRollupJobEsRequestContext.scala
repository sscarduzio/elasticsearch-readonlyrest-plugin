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
import cats.implicits.*
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.*
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RequestedIndex
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

class PutRollupJobEsRequestContext private(actionRequest: ActionRequest,
                                           esContext: EsContext,
                                           aclContext: AccessControlStaticContext,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val originIndices = {
    val config = on(actionRequest).call("getConfig").get[AnyRef]()
    val indexPattern = on(config).call("getIndexPattern").get[String]()
    val rollupIndex = on(config).call("getRollupIndex").get[String]()
    (ClusterIndexName.fromString(indexPattern) :: ClusterIndexName.fromString(rollupIndex) :: Nil).flatten.toCovariantSet
  }

  override protected def requestedIndicesFrom(request: ActionRequest): Set[RequestedIndex[ClusterIndexName]] = originIndices

  override protected def update(request: ActionRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    if(originIndices == filteredIndices.toList.toCovariantSet) {
      Modified
    } else {
      logger.error(s"[${id.show}] Write request with indices requires the same set of indices after filtering as at the beginning. Please report the issue.")
      ShouldBeInterrupted
    }
  }
}

object PutRollupJobEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[PutRollupJobEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getName.endsWith("PutRollupJobAction$Request")) {
      Some(new PutRollupJobEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}