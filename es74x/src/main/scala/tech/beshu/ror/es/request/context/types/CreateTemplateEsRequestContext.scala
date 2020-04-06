package tech.beshu.ror.es.request.context.types

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{IndexPattern, Template}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

class CreateTemplateEsRequestContext(actionRequest: PutIndexTemplateRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseEsRequestContext[TemplateRequestBlockContext](esContext, clusterService)
    with EsRequest[TemplateRequestBlockContext] {

  override val initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    templatesFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = ???

  private def templatesFrom(request: PutIndexTemplateRequest) = Set {
    val template = for {
      templateName <- NonEmptyString.unapply(request.name())
      indexPatterns <- request.indices
        .asSafeSet
        .flatMap(IndexPattern.from)
        .toNonEmptySet
    } yield Template(templateName, indexPatterns)
    template.getOrElse(throw RequestSeemsToBeInvalid[PutIndexTemplateRequest]("Template data is invalid"))
  }
}
