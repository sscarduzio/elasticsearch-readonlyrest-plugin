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

import monix.eval.Task
import org.elasticsearch.action.datastreams.GetDataStreamAction
import org.elasticsearch.action.datastreams.GetDataStreamAction.Response
import org.elasticsearch.index.Index
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseDataStreamsEsRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class GetDataStreamEsRequestContext(actionRequest: GetDataStreamAction.Request,
                                    esContext: EsContext,
                                    clusterService: RorClusterService,
                                    override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  private lazy val originDataStreams =
    actionRequest
      .getNames.asSafeSet
      .flatMap(DataStreamName.fromString)

  override protected def dataStreamsFrom(request: GetDataStreamAction.Request): Set[DataStreamName] =
    originDataStreams

  override protected def backingIndicesFrom(request: GetDataStreamAction.Request): BackingIndices =
    BackingIndices.IndicesInvolved(
      filteredIndices = Set.empty,
      allAllowedIndices = Set(ClusterIndexName.Local.wildcard)
    )

  override def modifyRequest(blockContext: BlockContext.DataStreamRequestBlockContext): ModificationResult = {
    setDataStreamNames(blockContext.dataStreams)
    ModificationResult.UpdateResponse {
      case r: GetDataStreamAction.Response =>
        blockContext.backingIndices match {
          case BackingIndices.IndicesInvolved(_, allAllowedIndices) =>
            Task.now(updateGetDataStreamResponse(r, extendAllowedIndicesSet(allAllowedIndices)))
          case BackingIndices.IndicesNotInvolved =>
            Task.now(r)
        }
      case r =>
        Task.now(r)
    }
  }

  private def extendAllowedIndicesSet(allowedIndices: Iterable[ClusterIndexName]) = {
    (allowedIndices.map(_.formatAsDataStreamBackingIndexName) ++ allowedIndices).toCovariantSet
  }

  private def setDataStreamNames(dataStreams: Set[DataStreamName]): Unit = {
    actionRequest.indices(dataStreams.map(DataStreamName.toString).toList: _*) // method is named indices but it sets data streams
  }

  private def updateGetDataStreamResponse(response: GetDataStreamAction.Response,
                                          allAllowedIndices: Iterable[ClusterIndexName]): GetDataStreamAction.Response = {
    val allowedIndicesMatcher = PatternsMatcher.create(allAllowedIndices)
    val filteredStreams =
      response
        .getDataStreams.asSafeList
        .filter { (dataStreamInfo: Response.DataStreamInfo) =>
          backingIndiesMatchesAllowedIndices(dataStreamInfo, allowedIndicesMatcher)
        }
    new GetDataStreamAction.Response(filteredStreams.asJava)
  }

  private def backingIndiesMatchesAllowedIndices(info: Response.DataStreamInfo,
                                                 allowedIndicesMatcher: PatternsMatcher[ClusterIndexName]) = {
    val dataStreamIndices: Set[ClusterIndexName] = indicesFrom(info).keySet.toCovariantSet
    val allowedBackingIndices = allowedIndicesMatcher.filter(dataStreamIndices)
    dataStreamIndices.diff(allowedBackingIndices).isEmpty
  }

  private def indicesFrom(response: Response.DataStreamInfo): Map[ClusterIndexName, Index] = {
    response
      .getDataStream
      .getIndices
      .asSafeList
      .flatMap { index =>
        Option(index.getName)
          .flatMap(ClusterIndexName.fromString)
          .map(clusterIndexName => (clusterIndexName, index))
      }
      .toMap
  }
}
