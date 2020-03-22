package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
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

class IndicesAliasesOperation private(request: IndicesAliasesRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object IndicesAliasesOperation {
  def from(request: IndicesAliasesRequest): IndicesAliasesOperation =
    new IndicesAliasesOperation(
      request,
      request.getAliasActions.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
    )
}

class IndicesAliasesOperationEsRequestContext(channel: RestChannel,
                                              override val taskId: Long,
                                              actionType: String,
                                              override val operation: IndicesAliasesOperation,
                                              override val actionRequest: IndicesAliasesRequest,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool,
                                              crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[IndicesAliasesOperationBlockContext, IndicesAliasesOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  )  with EsRequest[IndicesAliasesOperationBlockContext]{

  override def emptyBlockContext: IndicesAliasesOperationBlockContext = new IndicesAliasesOperationBlockContext(this)

  override protected def modifyRequest(blockContext: IndicesAliasesOperationBlockContext): Unit = ???
}

class IndicesAliasesOperationBlockContext(override val requestContext: RequestContext.Aux[IndicesAliasesOperation, IndicesAliasesOperationBlockContext] )
  extends GeneralIndexOperationBlockContext[IndicesAliasesOperationBlockContext] {

  override type OPERATION = IndicesAliasesOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): IndicesAliasesOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): IndicesAliasesOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): IndicesAliasesOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): IndicesAliasesOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): IndicesAliasesOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): IndicesAliasesOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): IndicesAliasesOperationBlockContext = ???
}