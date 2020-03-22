package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
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

class IndicesShardStoresOperation private(request: IndicesShardStoresRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object IndicesShardStoresOperation {
  def from(request: IndicesShardStoresRequest): IndicesShardStoresOperation =
    new IndicesShardStoresOperation(
      request,
      {
        val indices = request.indices.asSafeSet.flatMap(IndexName.fromString)
        if (indices.isEmpty) Set(IndexName.wildcard) else indices
      }
    )
}

class IndicesShardStoresOperationEsRequestContext(channel: RestChannel,
                                                  override val taskId: Long,
                                                  actionType: String,
                                                  override val operation: IndicesShardStoresOperation,
                                                  override val actionRequest: IndicesShardStoresRequest,
                                                  clusterService: RorClusterService,
                                                  override val threadPool: ThreadPool,
                                                  crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[IndicesShardStoresOperationBlockContext, IndicesShardStoresOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[IndicesShardStoresOperationBlockContext] {

  override def emptyBlockContext: IndicesShardStoresOperationBlockContext = new IndicesShardStoresOperationBlockContext(this)

  override protected def modifyRequest(blockContext: IndicesShardStoresOperationBlockContext): Unit = ???
}

class IndicesShardStoresOperationBlockContext(override val requestContext: RequestContext.Aux[IndicesShardStoresOperation, IndicesShardStoresOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[IndicesShardStoresOperationBlockContext] {

  override type OPERATION = IndicesShardStoresOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): IndicesShardStoresOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): IndicesShardStoresOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): IndicesShardStoresOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): IndicesShardStoresOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): IndicesShardStoresOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): IndicesShardStoresOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): IndicesShardStoresOperationBlockContext = ???
}