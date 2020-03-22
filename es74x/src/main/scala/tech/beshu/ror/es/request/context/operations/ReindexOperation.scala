package tech.beshu.ror.es.request.context.operations

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, GeneralIndexOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.util.Try

class ReindexOperation private(request: ReindexRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object ReindexOperation extends Logging {
  def from(request: ReindexRequest): ReindexOperation =
    new ReindexOperation(
      request,
      indicesFrom(request)
    )

  private def indicesFrom(request: ReindexRequest) = {
    Try {
      val sr = invokeMethodCached(request, request.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
      val ir = invokeMethodCached(request, request.getClass, "getDestination").asInstanceOf[IndexRequest]
      sr.indices.asSafeSet ++ ir.indices.asSafeSet
    } fold(
      ex => {
        logger.errorEx(s"Cannot extract indices from ReindexRequest", ex)
        Set.empty[String]
      },
      identity
    ) flatMap {
      IndexName.fromString
    }
  }
}

class ReindexOperationEsRequestContext(channel: RestChannel,
                                       override val taskId: Long,
                                       actionType: String,
                                       override val operation: ReindexOperation,
                                       override val actionRequest: ReindexRequest,
                                       clusterService: RorClusterService,
                                       override val threadPool: ThreadPool,
                                       crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[ReindexOperationBlockContext, ReindexOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[ReindexOperationBlockContext] {

  override def emptyBlockContext: ReindexOperationBlockContext = new ReindexOperationBlockContext(this)

  override protected def modifyRequest(blockContext: ReindexOperationBlockContext): Unit = ???
}

class ReindexOperationBlockContext(override val requestContext: RequestContext.Aux[ReindexOperation, ReindexOperationBlockContext])
  extends GeneralIndexOperationBlockContext[ReindexOperationBlockContext] {

  override type OPERATION = ReindexOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): ReindexOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): ReindexOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): ReindexOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): ReindexOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): ReindexOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): ReindexOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): ReindexOperationBlockContext = ???
}