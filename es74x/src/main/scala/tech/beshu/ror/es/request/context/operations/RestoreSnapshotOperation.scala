package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
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

class RestoreSnapshotOperation private(request: RestoreSnapshotRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object RestoreSnapshotOperation {
  def from(request: RestoreSnapshotRequest): RestoreSnapshotOperation =
    new RestoreSnapshotOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class RestoreSnapshotOperationEsRequestContext(channel: RestChannel,
                                               override val taskId: Long,
                                               actionType: String,
                                               override val operation: RestoreSnapshotOperation,
                                               override val actionRequest: RestoreSnapshotRequest,
                                               clusterService: RorClusterService,
                                               override val threadPool: ThreadPool,
                                               crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[RestoreSnapshotOperationBlockContext, RestoreSnapshotOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[RestoreSnapshotOperationBlockContext] {

  override def emptyBlockContext: RestoreSnapshotOperationBlockContext = new RestoreSnapshotOperationBlockContext(this)

  override protected def modifyRequest(blockContext: RestoreSnapshotOperationBlockContext): Unit = ???
}

class RestoreSnapshotOperationBlockContext(override val requestContext: RequestContext.Aux[RestoreSnapshotOperation, RestoreSnapshotOperationBlockContext])
  extends GeneralIndexOperationBlockContext[RestoreSnapshotOperationBlockContext] {

  override type OPERATION = RestoreSnapshotOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): RestoreSnapshotOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): RestoreSnapshotOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): RestoreSnapshotOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): RestoreSnapshotOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): RestoreSnapshotOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): RestoreSnapshotOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): RestoreSnapshotOperationBlockContext = ???
}