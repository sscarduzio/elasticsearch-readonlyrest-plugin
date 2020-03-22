package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.termvectors.MultiTermVectorsRequest
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

class MultiTermVectorsOperation private(request: MultiTermVectorsRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object MultiTermVectorsOperation {
  def from(request: MultiTermVectorsRequest): MultiTermVectorsOperation =
    new MultiTermVectorsOperation(
      request,
      request.getRequests.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
    )
}

class MultiTermVectorsOperationEsRequestContext(channel: RestChannel,
                                                override val taskId: Long,
                                                actionType: String,
                                                override val operation: MultiTermVectorsOperation,
                                                override val actionRequest: MultiTermVectorsRequest,
                                                clusterService: RorClusterService,
                                                override val threadPool: ThreadPool,
                                                crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[MultiTermVectorsOperationBlockContext, MultiTermVectorsOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[MultiTermVectorsOperationBlockContext] {

  override def emptyBlockContext: MultiTermVectorsOperationBlockContext = new MultiTermVectorsOperationBlockContext(this)

  override protected def modifyRequest(blockContext: MultiTermVectorsOperationBlockContext): Unit = ???
}

class MultiTermVectorsOperationBlockContext(override val requestContext: RequestContext.Aux[MultiTermVectorsOperation, MultiTermVectorsOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[MultiTermVectorsOperationBlockContext] {

  override type OPERATION = MultiTermVectorsOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): MultiTermVectorsOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): MultiTermVectorsOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): MultiTermVectorsOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): MultiTermVectorsOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): MultiTermVectorsOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): MultiTermVectorsOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): MultiTermVectorsOperationBlockContext = ???
}