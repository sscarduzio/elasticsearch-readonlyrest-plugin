package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.delete.DeleteRequest
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

class DeleteDocumentOperation private(request: DeleteRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object DeleteDocumentOperation {
  def from(request: DeleteRequest): DeleteDocumentOperation =
    new DeleteDocumentOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class DeleteDocumentOperationEsRequestContext(channel: RestChannel,
                                              override val taskId: Long,
                                              actionType: String,
                                              override val operation: DeleteDocumentOperation,
                                              override val actionRequest: DeleteRequest,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool,
                                              crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[DeleteDocumentOperationBlockContext, DeleteDocumentOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[DeleteDocumentOperationBlockContext] {

  override def emptyBlockContext: DeleteDocumentOperationBlockContext = new DeleteDocumentOperationBlockContext(this)

  override protected def modifyRequest(blockContext: DeleteDocumentOperationBlockContext): Unit = ???
}

class DeleteDocumentOperationBlockContext(override val requestContext: RequestContext.Aux[DeleteDocumentOperation, DeleteDocumentOperationBlockContext])
  extends GeneralIndexOperationBlockContext[DeleteDocumentOperationBlockContext] {

  override type OPERATION = DeleteDocumentOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): DeleteDocumentOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): DeleteDocumentOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): DeleteDocumentOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): DeleteDocumentOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): DeleteDocumentOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): DeleteDocumentOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): DeleteDocumentOperationBlockContext = ???
}