package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, NonIndexOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}

class GeneralNonIndexOperation(request: ActionRequest)
  extends Operation.NonIndexOperation

class GeneralNonIndexOperationEsRequestContext(channel: RestChannel,
                                               override val taskId: Long,
                                               actionType: String,
                                               override val operation: GeneralNonIndexOperation,
                                               actionRequest: ActionRequest,
                                               clusterService: RorClusterService,
                                               override val threadPool: ThreadPool,
                                               crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[GeneralNonIndexOperationBlockContext, GeneralNonIndexOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[GeneralNonIndexOperationBlockContext] {

  override def emptyBlockContext: GeneralNonIndexOperationBlockContext = new GeneralNonIndexOperationBlockContext(this)

  override protected def modifyRequest(blockContext: GeneralNonIndexOperationBlockContext): Unit = ???
}

class GeneralNonIndexOperationBlockContext(override val requestContext: RequestContext.Aux[GeneralNonIndexOperation, GeneralNonIndexOperationBlockContext])
  extends NonIndexOperationBlockContext[GeneralNonIndexOperationBlockContext] {

  override type OPERATION = GeneralNonIndexOperation

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): GeneralNonIndexOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): GeneralNonIndexOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withIndices(indices: Set[domain.IndexName]): GeneralNonIndexOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withRepositories(indices: Set[domain.IndexName]): GeneralNonIndexOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withSnapshots(indices: Set[domain.IndexName]): GeneralNonIndexOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): GeneralNonIndexOperationBlockContext = ???
}