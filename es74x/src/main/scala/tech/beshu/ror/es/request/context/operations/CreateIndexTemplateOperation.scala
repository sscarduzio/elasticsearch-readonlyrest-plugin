package tech.beshu.ror.es.request.context.operations

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, CreateTemplateOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.{IndexPattern, Operation, Template}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, RequestSeemsToBeInvalid}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

class CreateIndexTemplateOperation private(request: PutIndexTemplateRequest,
                                           template: Template)
  extends Operation.TemplateOperation.Create(template)

object CreateIndexTemplateOperation {
  def from(request: PutIndexTemplateRequest): CreateIndexTemplateOperation =
    new CreateIndexTemplateOperation(
      request,
      templateFrom(request)
    )

  private def templateFrom(request: PutIndexTemplateRequest) = {
    val template = for {
      templateName <- NonEmptyString.unapply(request.name())
      indexPatterns <- request.indices
        .asSafeSet
        .flatMap(IndexPattern.from)
        .toNonEmptySet
    } yield Template(templateName, indexPatterns)
    template.getOrElse(throw RequestSeemsToBeInvalid[PutIndexTemplateRequest]())
  }
}

class CreateTemplateOperationEsRequestContext(channel: RestChannel,
                                              override val taskId: Long,
                                              actionType: String,
                                              override val operation: CreateIndexTemplateOperation,
                                              override val actionRequest: PutIndexTemplateRequest,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool,
                                              crossClusterSearchEnabled: Boolean)
  extends BaseEsRequestContext[CreateIndexTemplateOperationBlockContext, CreateIndexTemplateOperation](
    channel, taskId, actionType, actionRequest, clusterService, threadPool, crossClusterSearchEnabled
  ) with EsRequest[CreateIndexTemplateOperationBlockContext] {

  override def emptyBlockContext: CreateIndexTemplateOperationBlockContext = new CreateIndexTemplateOperationBlockContext(this)

  override protected def modifyRequest(blockContext: CreateIndexTemplateOperationBlockContext): Unit = ???
}

class CreateIndexTemplateOperationBlockContext(override val requestContext: RequestContext.Aux[CreateIndexTemplateOperation, CreateIndexTemplateOperationBlockContext])
  extends CreateTemplateOperationBlockContext[CreateIndexTemplateOperationBlockContext] {

  override type OPERATION = CreateIndexTemplateOperation

  override def responseHeaders: Set[domain.Header] = ???

  override def withAddedResponseHeader(header: domain.Header): CreateIndexTemplateOperationBlockContext = ???

  override def contextHeaders: Set[domain.Header] = ???

  override def withAddedContextHeader(header: domain.Header): CreateIndexTemplateOperationBlockContext = ???

  override def indices: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withIndices(indices: Set[domain.IndexName]): CreateIndexTemplateOperationBlockContext = ???

  override def repositories: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withRepositories(indices: Set[domain.IndexName]): CreateIndexTemplateOperationBlockContext = ???

  override def snapshots: BlockContext.Outcome[Set[domain.IndexName]] = ???

  override def withSnapshots(indices: Set[domain.IndexName]): CreateIndexTemplateOperationBlockContext = ???

  override def userMetadata: UserMetadata = ???

  override def withUserMetadata(update: UserMetadata => UserMetadata): CreateIndexTemplateOperationBlockContext = ???
}