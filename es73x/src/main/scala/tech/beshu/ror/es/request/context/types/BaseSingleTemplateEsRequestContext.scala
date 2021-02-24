/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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

  override protected def templatesFrom(request: R): Set[Template] = Set(templateFrom(request))

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    val templates = blockContext.templates
    if (templates.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one template. First was taken. The whole set of templates [${templates.toList.mkString(",")}]")
    }
    update(actionRequest, templates.head)
  }

}
