package tech.beshu.ror.es.request.context.operations

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, GeneralIndexOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class ReflectionBasedIndicesOperation private(request: ActionRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object ReflectionBasedIndicesOperation {
  def from(request: ActionRequest): Option[ReflectionBasedIndicesOperation] =
    indicesFrom(request).map(new ReflectionBasedIndicesOperation(request, _))

  private def indicesFrom(request: ActionRequest) = {
    NonEmptyList
      .fromList(extractStringArrayFromPrivateMethod("indices", request).asSafeList)
      .orElse(NonEmptyList.fromList(extractStringArrayFromPrivateMethod("index", request).asSafeList))
      .map(indices => indices.toList.toSet.flatMap(IndexName.fromString))
  }
}

class ReflectionBasedIndicesOperationEsRequestContext(channel: RestChannel,
                                                      override val taskId: Long,
                                                      actionType: String,
                                                      override val operation: ReflectionBasedIndicesOperation,
                                                      override val actionRequest: ActionRequest,
                                                      clusterService: RorClusterService,
                                                      override val threadPool: ThreadPool,
                                                      crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[ReflectionBasedIndicesOperationBlockContext, ReflectionBasedIndicesOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[ReflectionBasedIndicesOperationBlockContext] {

  override def emptyBlockContext: ReflectionBasedIndicesOperationBlockContext = new ReflectionBasedIndicesOperationBlockContext(this)

  override protected def modifyRequest(blockContext: ReflectionBasedIndicesOperationBlockContext): Unit = ???
}


class ReflectionBasedIndicesOperationBlockContext(override val requestContext: RequestContext.Aux[ReflectionBasedIndicesOperation, ReflectionBasedIndicesOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[ReflectionBasedIndicesOperationBlockContext] {

  override type OPERATION = ReflectionBasedIndicesOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): ReflectionBasedIndicesOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): ReflectionBasedIndicesOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): ReflectionBasedIndicesOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): ReflectionBasedIndicesOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): ReflectionBasedIndicesOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): ReflectionBasedIndicesOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): ReflectionBasedIndicesOperationBlockContext = ???
}