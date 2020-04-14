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

import java.util.UUID

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GetTemplatesEsRequestContext(actionRequest: GetIndexTemplatesRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def templateFroms(request: GetIndexTemplatesRequest): Set[Template] = {
    val templatesFromRequest = request
      .names().asSafeSet
      .flatMap(templateFrom)
    if (templatesFromRequest.nonEmpty) templatesFromRequest
    else clusterService.allTemplates
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    val templates = blockContext.templates
    if (templates.isEmpty) {
      // hack! there is no other way to return empty list of templates (at the moment should not be used, but
      // I leave it as a protection
      actionRequest.names(UUID.randomUUID + "*")
      Modified
    } else {
      actionRequest.names(templates.toList.map(_.name.value.value): _*)
      Modified
    }
  }

  private def templateFrom(name: String) = {
    NonEmptyString
      .from(name)
      .map(TemplateName.apply)
      .map { templateName =>
        clusterService.getTemplate(templateName) match {
          case Some(template) => template
          case None => Template(templateName, UniqueNonEmptyList.of(IndexName.wildcard))
        }
      }
      .toOption
  }
}
