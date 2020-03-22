package tech.beshu.ror.es.request.context.operations

import cats.data.NonEmptySet
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, GeneralIndexOperationBlockContext, RepositoryOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation, Repository}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}


class AnyRepositoryOperation private(repositories: NonEmptySet[Repository],
                                     modifyRequest: Set[Repository] => ())
  extends Operation.RepositoryOperation(repositories)

object AnyRepositoryOperation {
  def from(request: RestoreSnapshotRequest): AnyRepositoryOperation = ???
//    new AnyRepositoryOperation(
//      request,
//      request.indices.asSafeSet.flatMap(IndexName.fromString)
//    )
}

class AnyRepositoryOperationEsRequestContext(channel: RestChannel,
                                             override val taskId: Long,
                                             actionType: String,
                                             override val operation: AnyRepositoryOperation,
                                             override val actionRequest: ActionRequest,
                                             clusterService: RorClusterService,
                                             override val threadPool: ThreadPool,
                                             crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[AnyRepositoryOperationBlockContext, AnyRepositoryOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[AnyRepositoryOperationBlockContext] {

  override def emptyBlockContext: AnyRepositoryOperationBlockContext = new AnyRepositoryOperationBlockContext(this)

  override protected def modifyRequest(blockContext: AnyRepositoryOperationBlockContext): Unit = ???
}

class AnyRepositoryOperationBlockContext(override val requestContext: RequestContext.Aux[AnyRepositoryOperation, AnyRepositoryOperationBlockContext])
  extends RepositoryOperationBlockContext[AnyRepositoryOperationBlockContext] {

  override type OPERATION = AnyRepositoryOperation

  override def repositories: BlockContext.Outcome[Set[Repository]] = ???

  override def withRepositories(indices: Set[Repository]): AnyRepositoryOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): AnyRepositoryOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): AnyRepositoryOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): AnyRepositoryOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): AnyRepositoryOperationBlockContext = ???

}