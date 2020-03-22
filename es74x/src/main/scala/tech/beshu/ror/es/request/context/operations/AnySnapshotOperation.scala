package tech.beshu.ror.es.request.context.operations

import cats.data.NonEmptySet
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, SnapshotOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation, Snapshot}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}

class AnySnapshotOperation private(snapshots: NonEmptySet[Snapshot],
                                   private [AnySnapshotOperation] val modifyRequest: Set[Snapshot] => ())
  extends Operation.SnapshotOperation(snapshots)

object AnySnapshotOperation {
  def from(request: GetSnapshotsRequest): AnySnapshotOperation = {
    request.
  }

  //    new AnySnapshotOperation(
  //      request,
  //      request.indices.asSafeSet.flatMap(IndexName.fromString)
  //    )
}

class AnySnapshotOperationEsRequestContext(channel: RestChannel,
                                             override val taskId: Long,
                                             actionType: String,
                                             override val operation: AnySnapshotOperation,
                                             override val actionRequest: ActionRequest,
                                             clusterService: RorClusterService,
                                             override val threadPool: ThreadPool,
                                             crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[AnySnapshotOperationBlockContext, AnySnapshotOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[AnySnapshotOperationBlockContext] {

  override def emptyBlockContext: AnySnapshotOperationBlockContext = new AnySnapshotOperationBlockContext(this)

  override protected def modifyRequest(blockContext: AnySnapshotOperationBlockContext): Unit = {
    blockContext.snapshots match {
      case Outcome.Exist(filteredSnapshots) => operation.modifyRequest(filteredSnapshots)
      case Outcome.NotExist =>
    }
  }
}

class AnySnapshotOperationBlockContext(override val requestContext: RequestContext.Aux[AnySnapshotOperation, AnySnapshotOperationBlockContext])
  extends SnapshotOperationBlockContext[AnySnapshotOperationBlockContext] {

  override type OPERATION = AnySnapshotOperation

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): AnySnapshotOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): AnySnapshotOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): AnySnapshotOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): AnySnapshotOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[Snapshot]] = ???

  override def withSnapshots(snapshots: Set[Snapshot]): AnySnapshotOperationBlockContext = ???
}