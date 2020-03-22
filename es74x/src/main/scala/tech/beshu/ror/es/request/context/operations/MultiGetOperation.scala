package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.get.MultiGetRequest
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

import scala.collection.JavaConverters._

class MultiGetOperation private(request: MultiGetRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object MultiGetOperation {
  def from(request: MultiGetRequest): MultiGetOperation =
    new MultiGetOperation(
      request,
      request.getItems.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
    )
}

class MultiGetOperationEsRequestContext(channel: RestChannel,
                                        override val taskId: Long,
                                        actionType: String,
                                        override val operation: MultiGetOperation,
                                        override val actionRequest: MultiGetRequest,
                                        clusterService: RorClusterService,
                                        override val threadPool: ThreadPool,
                                        crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[MultiGetOperationBlockContext, MultiGetOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[MultiGetOperationBlockContext] {

  override def emptyBlockContext: MultiGetOperationBlockContext = new MultiGetOperationBlockContext(this)

  override protected def modifyRequest(blockContext: MultiGetOperationBlockContext): Unit = ???
}

class MultiGetOperationBlockContext(override val requestContext: RequestContext.Aux[MultiGetOperation, MultiGetOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[MultiGetOperationBlockContext] {

  override type OPERATION = MultiGetOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): MultiGetOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): MultiGetOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): MultiGetOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): MultiGetOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): MultiGetOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): MultiGetOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): MultiGetOperationBlockContext = ???
}