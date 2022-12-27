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

import org.elasticsearch.action.datastreams.ModifyDataStreamsAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, DataStreamName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class ModifyDataStreamsEsRequestContext(actionRequest: ModifyDataStreamsAction.Request,
                                        esContext: EsContext,
                                        clusterService: RorClusterService,
                                        override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  private lazy val originIndices: Set[domain.ClusterIndexName] = {
    actionRequest.getActions.asSafeList.map(_.getIndex).flatMap(ClusterIndexName.fromString).toSet
  }

  override protected def backingIndicesFrom(request: ModifyDataStreamsAction.Request): DataStreamRequestBlockContext.BackingIndices =
    BackingIndices.IndicesInvolved(originIndices, Set(ClusterIndexName.Local.wildcard))

  override def dataStreamsFrom(request: ModifyDataStreamsAction.Request): Set[domain.DataStreamName] = {
    request.getActions.asSafeList.map(_.getDataStream).flatMap(DataStreamName.fromString).toSet
  }

  override def modifyRequest(blockContext: BlockContext.DataStreamRequestBlockContext): ModificationResult = {
    ModificationResult.Modified // data stream and indices already processed by ACL
  }
}
