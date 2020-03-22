package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.{ActionRequest, IndicesRequest}
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

class IndicesReplaceableOperation private(request: ActionRequest with IndicesRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object IndicesReplaceableOperation {
  def from(request: ActionRequest with IndicesRequest): IndicesReplaceableOperation =
    new IndicesReplaceableOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class IndicesReplaceableOperationEsRequestContext(channel: RestChannel,
                                                  override val taskId: Long,
                                                  actionType: String,
                                                  override val operation: IndicesReplaceableOperation,
                                                  override val actionRequest: ActionRequest with IndicesRequest,
                                                  clusterService: RorClusterService,
                                                  override val threadPool: ThreadPool,
                                                  crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[IndicesReplaceableOperationBlockContext, IndicesReplaceableOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[IndicesReplaceableOperationBlockContext] {

  override def emptyBlockContext: IndicesReplaceableOperationBlockContext = new IndicesReplaceableOperationBlockContext(this)

  override protected def modifyRequest(blockContext: IndicesReplaceableOperationBlockContext): Unit = ???
}

class IndicesReplaceableOperationBlockContext(override val requestContext: RequestContext.Aux[IndicesReplaceableOperation, IndicesReplaceableOperationBlockContext])
  extends GeneralIndexOperationBlockContext[IndicesReplaceableOperationBlockContext] {

  override type OPERATION = IndicesReplaceableOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): IndicesReplaceableOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): IndicesReplaceableOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): IndicesReplaceableOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): IndicesReplaceableOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): IndicesReplaceableOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): IndicesReplaceableOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): IndicesReplaceableOperationBlockContext = ???
}