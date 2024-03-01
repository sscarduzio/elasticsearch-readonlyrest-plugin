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

import org.elasticsearch.action.datastreams.MigrateToDataStreamAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseDataStreamsEsRequestContext

class MigrateToDataStreamEsRequestContext(actionRequest: MigrateToDataStreamAction.Request,
                                          esContext: EsContext,
                                          clusterService: RorClusterService,
                                          override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  private lazy val originIndices = Option(actionRequest.getAliasName).flatMap(ClusterIndexName.fromString).toSet

  override protected def dataStreamsFrom(request: MigrateToDataStreamAction.Request): Set[domain.DataStreamName] = Set.empty

  override protected def backingIndicesFrom(request: MigrateToDataStreamAction.Request): BackingIndices = {
    BackingIndices.IndicesInvolved(filteredIndices = originIndices, allAllowedIndices = Set(ClusterIndexName.Local.wildcard))
  }

  override def modifyRequest(blockContext: BlockContext.DataStreamRequestBlockContext): ModificationResult = {
    ModificationResult.Modified // data stream and indices already processed by ACL
  }
}
