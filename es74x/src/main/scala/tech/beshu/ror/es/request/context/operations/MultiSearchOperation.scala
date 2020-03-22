package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.search.MultiSearchRequest
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

class MultiSearchOperation private(request: MultiSearchRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object MultiSearchOperation {
  def from(request: MultiSearchRequest): MultiSearchOperation =
    new MultiSearchOperation(
      request,
      request.requests().asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
    )
}

class MultiSearchOperationEsRequestContext(channel: RestChannel,
                                           override val taskId: Long,
                                           actionType: String,
                                           override val operation: MultiSearchOperation,
                                           override val actionRequest: MultiSearchRequest,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool,
                                           crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[MultiSearchOperationBlockContext, MultiSearchOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[MultiSearchOperationBlockContext] {

  override def emptyBlockContext: MultiSearchOperationBlockContext = new MultiSearchOperationBlockContext(this)

  override protected def modifyRequest(blockContext: MultiSearchOperationBlockContext): Unit = ???
}

class MultiSearchOperationBlockContext(override val requestContext: RequestContext.Aux[MultiSearchOperation, MultiSearchOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[MultiSearchOperationBlockContext] {

  override type OPERATION = MultiSearchOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): MultiSearchOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): MultiSearchOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): MultiSearchOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): MultiSearchOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): MultiSearchOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): MultiSearchOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): MultiSearchOperationBlockContext = ???
}