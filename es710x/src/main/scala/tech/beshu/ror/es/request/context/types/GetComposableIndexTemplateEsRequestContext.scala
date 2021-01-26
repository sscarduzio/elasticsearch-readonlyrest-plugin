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
import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateName}
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{ComponentTemplate, IndexTemplate}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GetComposableIndexTemplateEsRequestContext(actionRequest: GetComposableIndexTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[GetComposableIndexTemplateAction.Request, IndexTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templatesFrom(request: GetComposableIndexTemplateAction.Request): Set[IndexTemplate] = {
    Option(request.name())
      .flatMap(templateFrom)
      .map(Set(_))
      .getOrElse(clusterService.allTemplates.collect { case it: IndexTemplate => it })
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult =  {
    val templatesStr = blockContext.templateOperations.toList match {
      case Nil =>
        // hack! there is no other way to return empty list of templates (at the moment should not be used, but
        // I leave it as a protection
        UUID.randomUUID + "*"
      case t :: Nil =>
        t.name.value.value match {
          case "*" => null
          case other => other
        }
      case ts =>
        ts.map(_.name.value.value).mkString(",")
    }
    actionRequest.name(templatesStr)
    Modified
  }

  private def templateFrom(name: String) = {
    NonEmptyString
      .from(name)
      .map(TemplateName.apply)
      .map { templateName =>
        clusterService.getTemplate(templateName) match {
          case Some(template: IndexTemplate) =>
            template
          case Some(_: ComponentTemplate) | None =>
            IndexTemplate(templateName, UniqueNonEmptyList.of(IndexName.wildcard), Set.empty)
        }
      }
      .toOption
  }
}
