package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.CurrentUserMetadataOperationBlockContext
import tech.beshu.ror.accesscontrol.domain.Operation.CurrentUserMetadataOperation
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.es.rradmin.RRAdminRequest

class CurrentUserMetadataOperationEsRequestContext(channel: RestChannel,
                                                   override val taskId: Long,
                                                   actionType: String,
                                                   override val operation: CurrentUserMetadataOperation.type,
                                                   override val actionRequest: RRAdminRequest,
                                                   clusterService: RorClusterService,
                                                   override val threadPool: ThreadPool,
                                                   crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[CurrentUserMetadataOperationBlockContext, CurrentUserMetadataOperation.type](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[CurrentUserMetadataOperationBlockContext] {

  override def emptyBlockContext: CurrentUserMetadataOperationBlockContext = new CurrentUserMetadataOperationBlockContext(this)

  override protected def modifyRequest(blockContext: CurrentUserMetadataOperationBlockContext): Unit = ???
}
