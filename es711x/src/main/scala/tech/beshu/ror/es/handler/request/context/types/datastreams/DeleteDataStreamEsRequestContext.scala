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

import cats.implicits.toShow
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.{ClassCanonicalName, MatchResult, ReflectionBasedDataStreamsEsContextCreator, tryUpdateDataStreams}
import tech.beshu.ror.es.handler.request.context.types.{BaseDataStreamsEsRequestContext, ReflectionBasedActionRequest}

private[datastreams] class DeleteDataStreamEsRequestContext private(actionRequest: ActionRequest,
                                                                    dataStreams: Set[DataStreamName],
                                                                    esContext: EsContext,
                                                                    clusterService: RorClusterService,
                                                                    override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def dataStreamsFrom(request: ActionRequest): Set[DataStreamName] = dataStreams

  override protected def backingIndicesFrom(request: ActionRequest): BackingIndices = BackingIndices.IndicesNotInvolved

  override def modifyRequest(blockContext: DataStreamRequestBlockContext): ModificationResult = {
    if (modifyActionRequest(blockContext)) {
      ModificationResult.Modified
    } else {
      logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getCanonicalName} request. We're using reflection to modify the request data streams and it fails. Please, report the issue.")
      ModificationResult.ShouldBeInterrupted
    }
  }

  private def modifyActionRequest(blockContext: DataStreamRequestBlockContext): Boolean = {
    tryUpdateDataStreams(
      actionRequest = actionRequest,
      dataStreamsFieldName = "names",
      dataStreams = blockContext.dataStreams
    )
  }
}

object DeleteDataStreamEsRequestContext extends ReflectionBasedDataStreamsEsContextCreator {

  override val actionRequestClass: ClassCanonicalName =
    ClassCanonicalName("org.elasticsearch.xpack.core.action.DeleteDataStreamAction.Request")

  override def unapply(arg: ReflectionBasedActionRequest): Option[DeleteDataStreamEsRequestContext] = {
    tryMatchActionRequestWithDataStreams(
      actionRequest = arg.esContext.actionRequest,
      getDataStreamsMethodName = "getNames"
    ) match {
      case MatchResult.Matched(dataStreams) =>
        Some(new DeleteDataStreamEsRequestContext(arg.esContext.actionRequest, dataStreams, arg.esContext, arg.clusterService, arg.threadPool))
      case MatchResult.NotMatched =>
        None
    }
  }
}