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
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.datastreams.ReflectionBasedDataStreamsEsRequestContext.*
import tech.beshu.ror.es.handler.request.context.types.{BaseDataStreamsEsRequestContext, ReflectionBasedActionRequest}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*
import scala.util.Try

private[datastreams] class GetDataStreamEsRequestContext(actionRequest: ActionRequest,
                                                         dataStreams: Set[DataStreamName],
                                                         esContext: EsContext,
                                                         clusterService: RorClusterService,
                                                         override val threadPool: ThreadPool)
  extends BaseDataStreamsEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def dataStreamsFrom(request: ActionRequest): Set[DataStreamName] = dataStreams

  override protected def backingIndicesFrom(request: ActionRequest): BackingIndices =
    BackingIndices.IndicesInvolved(
      filteredIndices = Set.empty,
      allAllowedIndices = Set(ClusterIndexName.Local.wildcard)
    )

  override def modifyRequest(blockContext: BlockContext.DataStreamRequestBlockContext): ModificationResult = {
    if (modifyActionRequest(blockContext)) {
      ModificationResult.UpdateResponse {
        case r: ActionResponse if isGetDataStreamActionResponse(r) =>
          blockContext.backingIndices match {
            case BackingIndices.IndicesInvolved(_, allAllowedIndices) =>
              Task.now(updateActionResponse(r, extendAllowedIndicesSet(allAllowedIndices)))
            case BackingIndices.IndicesNotInvolved =>
              Task.now(r)
          }
        case r =>
          Task.now(r)
      }
    } else {
      logger.error(s"[${id.show}] Cannot update ${actionRequest.getClass.getCanonicalName.show} request. We're using reflection to modify the request data streams and it fails. Please, report the issue.")
      ModificationResult.ShouldBeInterrupted
    }
  }

  private def extendAllowedIndicesSet(allowedIndices: Iterable[ClusterIndexName]) = {
    allowedIndices.toList.map(_.formatAsLegacyDataStreamBackingIndexName).toCovariantSet ++ allowedIndices.toCovariantSet
  }

  private def modifyActionRequest(blockContext: BlockContext.DataStreamRequestBlockContext): Boolean = {
    tryUpdateDataStreams(
      actionRequest = actionRequest,
      dataStreamsFieldName = "names",
      dataStreams = blockContext.dataStreams
    )
  }

  private def isGetDataStreamActionResponse(r: ActionResponse) = {
    r.getClass.getCanonicalName == "org.elasticsearch.xpack.core.action.GetDataStreamAction.Response"
  }

  private def updateActionResponse(response: ActionResponse,
                                   allAllowedIndices: Iterable[ClusterIndexName]): ActionResponse = {
    val allowedIndicesMatcher = PatternsMatcher.create(allAllowedIndices)
    val filteredDataStreams = on(response).call("getDataStreams").get[java.util.List[Object]]()
        .asScala
        .filter { dataStreamInfo =>
          backingIndiesMatchesAllowedIndices(dataStreamInfo, allowedIndicesMatcher)
        }

    on(response).set("dataStreams", filteredDataStreams.asJava)
    response
  }

  private def backingIndiesMatchesAllowedIndices(info: Object, allowedIndicesMatcher: PatternsMatcher[ClusterIndexName]): Boolean = {
    val dataStreamIndices = indicesFromDataStreamInfo(info).get
    val allowedBackingIndices = allowedIndicesMatcher.filter(dataStreamIndices)
    dataStreamIndices.diff(allowedBackingIndices).isEmpty
  }

  private def indicesFromDataStreamInfo(info: Object): Try[Set[ClusterIndexName]] = {
    for {
      dataStream <- Try(on(info).call("getDataStream").get[AnyVal]())
      backingIndices <- Try (on(dataStream).call("getIndices").get[java.util.List[Object]]().asSafeList)
      indices <- Try {
        backingIndices
          .flatMap(backingIndex => Option(on(backingIndex).call("getName").get[String]))
          .flatMap(ClusterIndexName.fromString)
          .toCovariantSet
      }
    } yield indices

  }
}

object GetDataStreamEsRequestContext extends ReflectionBasedDataStreamsEsContextCreator {

  override val actionRequestClass: ClassCanonicalName =
    ClassCanonicalName("org.elasticsearch.xpack.core.action.GetDataStreamAction.Request")

  override def unapply(arg: ReflectionBasedActionRequest): Option[GetDataStreamEsRequestContext] = {
    tryMatchActionRequestWithDataStreams(
      actionRequest = arg.esContext.actionRequest,
      getDataStreamsMethodName = "getNames"
    ) match {
      case MatchResult.Matched(dataStreams) =>
        Some(new GetDataStreamEsRequestContext(arg.esContext.actionRequest, dataStreams, arg.esContext, arg.clusterService, arg.threadPool))
      case MatchResult.NotMatched() =>
        None
    }
  }
}
