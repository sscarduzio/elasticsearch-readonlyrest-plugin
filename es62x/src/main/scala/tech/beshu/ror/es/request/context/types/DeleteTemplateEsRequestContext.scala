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

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class DeleteTemplateEsRequestContext(actionRequest: DeleteIndexTemplateRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseSingleTemplateEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def templateFrom(request: DeleteIndexTemplateRequest): Template = {
    val templateName = NonEmptyString
      .from(request.name())
      .map(TemplateName.apply)
      .getOrElse(throw RequestSeemsToBeInvalid[DeleteIndexTemplateRequest]("DeleteIndexTemplateRequest template name cannot be empty"))

    clusterService.getTemplate(templateName) match {
      case Some(template) => template
      case None => Template(templateName, UniqueNonEmptyList.of(IndexName.wildcard))
    }
  }

  override protected def update(request: DeleteIndexTemplateRequest, template: Template): ModificationResult =
  // nothing to modify - if it was filtered, we are good
    ModificationResult.Modified
}