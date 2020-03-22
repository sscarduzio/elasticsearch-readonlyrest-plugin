package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.search.SearchRequest
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

class SearchOperation private(request: SearchRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object SearchOperation {
  def from(request: SearchRequest): SearchOperation =
    new SearchOperation(
      request,
      request.indices.asSafeSet.flatMap(IndexName.fromString)
    )
}

class SearchOperationEsRequestContext(channel: RestChannel,
                                      override val taskId: Long,
                                      actionType: String,
                                      override val operation: SearchOperation,
                                      override val actionRequest: SearchRequest,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool,
                                      crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[SearchOperationBlockContext, SearchOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[SearchOperationBlockContext] {

  override def emptyBlockContext: SearchOperationBlockContext = new SearchOperationBlockContext(this)

  override protected def modifyRequest(blockContext: SearchOperationBlockContext): Unit = ???
}

class SearchOperationBlockContext(override val requestContext: RequestContext.Aux[SearchOperation, SearchOperationBlockContext])
  extends GeneralIndexOperationBlockContext[SearchOperationBlockContext] {

  override type OPERATION = SearchOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): SearchOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): SearchOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): SearchOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): SearchOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): SearchOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): SearchOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): SearchOperationBlockContext = ???
}