package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, GeneralIndexOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class IndicesStatsOperation private(request: IndicesStatsRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object IndicesStatsOperation {
  def from(request: IndicesStatsRequest): IndicesStatsOperation =
    new IndicesStatsOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class IndicesStatsOperationEsRequestContext(channel: RestChannel,
                                            override val taskId: Long,
                                            actionType: String,
                                            override val operation: IndicesStatsOperation,
                                            override val actionRequest: IndicesStatsRequest,
                                            clusterService: RorClusterService,
                                            override val threadPool: ThreadPool,
                                            crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[IndicesStatsOperationBlockContext, IndicesStatsOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[IndicesStatsOperationBlockContext] {

  override def emptyBlockContext: IndicesStatsOperationBlockContext = new IndicesStatsOperationBlockContext(this)

  override protected def modifyRequest(blockContext: IndicesStatsOperationBlockContext): Unit = ???
}

class IndicesStatsOperationBlockContext(override val requestContext: RequestContext.Aux[IndicesStatsOperation, IndicesStatsOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[IndicesStatsOperationBlockContext] {

  override type OPERATION = IndicesStatsOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): IndicesStatsOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): IndicesStatsOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): IndicesStatsOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): IndicesStatsOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): IndicesStatsOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): IndicesStatsOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): IndicesStatsOperationBlockContext = ???
}