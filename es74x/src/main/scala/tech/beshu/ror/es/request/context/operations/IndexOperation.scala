package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.index.IndexRequest
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

class IndexOperation private(request: IndexRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object IndexOperation {
  def from(request: IndexRequest): IndexOperation =
    new IndexOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class IndexOperationEsRequestContext(channel: RestChannel,
                                     override val taskId: Long,
                                     actionType: String,
                                     override val operation: IndexOperation,
                                     override val actionRequest: IndexRequest,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool,
                                     crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[IndexOperationBlockContext, IndexOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[IndexOperationBlockContext] {

  override def emptyBlockContext: IndexOperationBlockContext = new IndexOperationBlockContext(this)

  override protected def modifyRequest(blockContext: IndexOperationBlockContext): Unit = ???
}

class IndexOperationBlockContext(override val requestContext: RequestContext.Aux[IndexOperation, IndexOperationBlockContext])
  extends GeneralIndexOperationBlockContext[IndexOperationBlockContext] {

  override type OPERATION = IndexOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): IndexOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): IndexOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): IndexOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): IndexOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): IndexOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): IndexOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): IndexOperationBlockContext = ???
}