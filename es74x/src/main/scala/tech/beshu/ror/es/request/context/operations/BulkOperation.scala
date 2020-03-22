package tech.beshu.ror.es.request.context.operations

import cats.implicits._
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.GeneralIndexOperationBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexName, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.collection.JavaConverters._

class BulkOperation private(request: BulkRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object BulkOperation {
  def from(request: BulkRequest): BulkOperation =
    new BulkOperation(
      request,
      request.requests().asScala.map(_.index()).flatMap(IndexName.fromString).toSet
    )
}

class BulkOperationEsRequestContext(channel: RestChannel,
                                    override val taskId: Long,
                                    actionType: String,
                                    override val operation: BulkOperation,
                                    override val actionRequest: BulkRequest,
                                    clusterService: RorClusterService,
                                    override val threadPool: ThreadPool,
                                    crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[BulkOperationBlockContext, BulkOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[BulkOperationBlockContext] {

  override def emptyBlockContext: BulkOperationBlockContext = new BulkOperationBlockContext(this)

  override protected def modifyRequest(blockContext: BulkOperationBlockContext): Unit = {
    blockContext.filteredIndices match {
      case Outcome.Exist(filtered) =>
        actionRequest.requests().removeIf { request: DocWriteRequest[_] => removeOrAlter(request, filtered) }
      case Outcome.NotExist =>
    }
  }

  private def removeOrAlter(request: DocWriteRequest[_], filteredIndices: Set[IndexName]) = {
    val expandedIndicesOfRequest = clusterService.expandIndices(request.indices.asSafeSet.flatMap(IndexName.fromString))
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case one :: Nil =>
        request.index(one.value.value)
        false
      case one :: _ =>
        request.index(one.value.value)
        logger.warn(
          s"""[$taskId] One of requests from BulkOperation contains more than one index after expanding and intersect.
             |Picking first from [${remaining.map(_.show).mkString(",")}]"""".stripMargin
        )
        false
    }
  }

}

class BulkOperationBlockContext(override val requestContext: RequestContext.Aux[BulkOperation, BulkOperationBlockContext])
  extends GeneralIndexOperationBlockContext[BulkOperationBlockContext] {

  override type OPERATION = BulkOperation

  override def filteredIndices: Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): BulkOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): BulkOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): BulkOperationBlockContext = ???

  override def indices: Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): BulkOperationBlockContext = ???

  override def repositories: Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): BulkOperationBlockContext = ???

  override def snapshots: Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): BulkOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): BulkOperationBlockContext = ???
}