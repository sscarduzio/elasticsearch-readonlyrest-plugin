package tech.beshu.ror.es.request.context.operations

import org.elasticsearch.action.ActionRequest
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
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class SearchTemplateOperation private(request: ActionRequest, override val indices: Set[domain.IndexName])
  extends Operation.GeneralIndexOperation(indices)

object SearchTemplateOperation {
  def from(request: ActionRequest): Option[SearchTemplateOperation] = {
    if (request.getClass.getSimpleName.startsWith("SearchTemplateRequest")) {
      Some(new SearchTemplateOperation(request, indicesFrom(request)))
    } else {
      None
    }
  }

  private def indicesFrom(request: ActionRequest) = {
    Option(invokeMethodCached(request, request.getClass, "getRequest"))
      .map(_.asInstanceOf[SearchRequest].indices.asSafeSet)
      .getOrElse(Set.empty)
      .flatMap(IndexName.fromString)
  }
}

class SearchTemplateOperationEsRequestContext(channel: RestChannel,
                                              override val taskId: Long,
                                              actionType: String,
                                              override val operation: SearchTemplateOperation,
                                              override val actionRequest: ActionRequest,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool,
                                              crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[SearchTemplateOperationBlockContext, SearchTemplateOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[SearchTemplateOperationBlockContext] {

  override def emptyBlockContext: SearchTemplateOperationBlockContext = new SearchTemplateOperationBlockContext(this)

  override protected def modifyRequest(blockContext: SearchTemplateOperationBlockContext): Unit = ???
}

class SearchTemplateOperationBlockContext(override val requestContext: RequestContext.Aux[SearchTemplateOperation, SearchTemplateOperationBlockContext])
  extends GeneralIndexOperationBlockContext[SearchTemplateOperationBlockContext] {

  override type OPERATION = SearchTemplateOperation

  override def filteredIndices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withFilteredIndices(indices: Set[IndexName]): SearchTemplateOperationBlockContext = ???

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): SearchTemplateOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): SearchTemplateOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[IndexName]] = ???

  override def withIndices(indices: Set[IndexName]): SearchTemplateOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[IndexName]] = ???

  override def withRepositories(indices: Set[IndexName]): SearchTemplateOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[IndexName]] = ???

  override def withSnapshots(indices: Set[IndexName]): SearchTemplateOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): SearchTemplateOperationBlockContext = ???
}
