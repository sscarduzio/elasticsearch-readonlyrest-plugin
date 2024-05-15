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

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.{ClassCanonicalName, MatchResult, ReflectionBasedDataStreamsEsContextCreator}
import tech.beshu.ror.es.handler.request.context.types.{BaseDataStreamsEsRequestContext, ReflectionBasedActionRequest}

private[datastreams] class PromoteDataStreamEsRequestContext private(actionRequest: ActionRequest,
                                                                     dataStreams: Set[DataStreamName],
                                                                     esContext: EsContext,
                                                                     clusterService: RorClusterService,
                                                                     override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def dataStreamsFrom(request: ActionRequest): Set[DataStreamName] = dataStreams

  override protected def backingIndicesFrom(request: ActionRequest): BackingIndices = BackingIndices.IndicesNotInvolved

  override protected def modifyRequest(blockContext: BlockContext.DataStreamRequestBlockContext): ModificationResult = {
    ModificationResult.Modified // data stream already processed by ACL
  }
}

object PromoteDataStreamEsRequestContext extends ReflectionBasedDataStreamsEsContextCreator {

  override val actionRequestClass: ClassCanonicalName =
    ClassCanonicalName("org.elasticsearch.xpack.core.action.PromoteDataStreamAction.Request")

  override def unapply(arg: ReflectionBasedActionRequest): Option[PromoteDataStreamEsRequestContext] = {
    tryMatchActionRequestWithDataStreams(
      actionRequest = arg.esContext.actionRequest,
      getDataStreamsMethodName = "indices"
    ) match {
      case MatchResult.Matched(dataStreams) =>
        Some(new PromoteDataStreamEsRequestContext(arg.esContext.actionRequest, dataStreams, arg.esContext, arg.clusterService, arg.threadPool))
      case MatchResult.NotMatched() =>
        None
    }
  }
}
