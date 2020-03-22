//package tech.beshu.ror.es.request.context.operations
//
//import org.elasticsearch.action.bulk.BulkRequest
//import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
//import org.elasticsearch.rest.RestChannel
//import org.elasticsearch.threadpool.ThreadPool
//import tech.beshu.ror.accesscontrol.domain
//import tech.beshu.ror.accesscontrol.domain.Operation
//import tech.beshu.ror.accesscontrol.request.GeneralIndexOperationRequestContext
//import tech.beshu.ror.es.RorClusterService
//import tech.beshu.ror.es.request.context.BaseEsRequestContext
//
//class CompositeIndicesOperation private(request: CompositeIndicesRequest, override val indices: Set[domain.IndexName])
//  extends Operation.GeneralIndexOperation(indices)
//
//object CompositeIndicesOperation {
//  def from(request: CompositeIndicesRequest): CompositeIndicesOperation =
//    new CompositeIndicesOperation(
//      request,
//      ???
//    )
//}
//
//class CompositeIndicesOperationEsRequestContext(channel: RestChannel,
//                                      override val taskId: Long,
//                                      actionType: String,
//                                      override val operation: CompositeIndicesOperation,
//                                                veractionRequest: CompositeIndicesRequest,
//                                      clusterService: RorClusterService,
//                                      threadPool: ThreadPool,
//                                      crossClusterSearchEnabled: Boolean)
//  extends BaseEsRequestContext[CompositeIndicesOperation](channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled)
//    with GeneralIndexOperationRequestContext[CompositeIndicesOperation]
//    with EsRequest[CompositeIndicesRequest]

// todo: fixme