package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest
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

class GetSettingsOperation private(request: GetSettingsRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object GetSettingsOperation {
  def from(request: GetSettingsRequest): GetSettingsOperation =
    new GetSettingsOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class GetSettingsOperationEsRequestContext(channel: RestChannel,
                                           override val taskId: Long,
                                           actionType: String,
                                           override val operation: GetSettingsOperation,
                                           override val actionRequest: GetSettingsRequest,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool,
                                           crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[GetSettingsOperationBlockContext, GetSettingsOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[GetSettingsOperationBlockContext] {

  override def emptyBlockContext: GetSettingsOperationBlockContext = new GetSettingsOperationBlockContext(this)

  override protected def modifyRequest(blockContext: GetSettingsOperationBlockContext): Unit = ???
}

class GetSettingsOperationBlockContext(override val requestContext: RequestContext.Aux[GetSettingsOperation, GetSettingsOperationBlockContext])
  extends GeneralIndexOperationBlockContext[GetSettingsOperationBlockContext] {

  override type OPERATION = GetSettingsOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): GetSettingsOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): GetSettingsOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): GetSettingsOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): GetSettingsOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): GetSettingsOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): GetSettingsOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): GetSettingsOperationBlockContext = ???
}
