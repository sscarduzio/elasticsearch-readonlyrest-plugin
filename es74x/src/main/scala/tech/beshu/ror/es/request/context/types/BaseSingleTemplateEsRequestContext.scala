package tech.beshu.ror.es.request.context.types

import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.Template
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

abstract class BaseSingleTemplateEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                      esContext: EsContext,
                                                                      clusterService: RorClusterService,
                                                                      override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  protected def templateFrom(request: R): Template

  protected def update(request: R, template: Template): ModificationResult

  override protected def templateFroms(request: R): Set[Template] = Set(templateFrom(request))

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    val templates = blockContext.templates
    if (templates.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one template. First was taken. Whole set of templates [${templates.toList.mkString(",")}]")
    }
    update(actionRequest, templates.head)
  }

}
