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
package tech.beshu.ror.es.handler.request.context.types.templates

import org.elasticsearch.action.admin.indices.template.put.PutComponentTemplateAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.AddingComponentTemplate
import tech.beshu.ror.accesscontrol.domain.{RequestedIndex, TemplateName}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class PutComponentTemplateEsRequestContext(actionRequest: PutComponentTemplateAction.Request,
                                           esContext: EsContext,
                                           override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[PutComponentTemplateAction.Request, AddingComponentTemplate](
    actionRequest, esContext, threadPool
  ) {

  override protected def templateOperationFrom(request: PutComponentTemplateAction.Request): AddingComponentTemplate = {
    val templateOperation = for {
      name <- TemplateName
        .fromString(request.name())
        .toRight("Template name should be non-empty")
      aliases = request.componentTemplate().template().aliases().asSafeMap.keys.flatMap(RequestedIndex.fromString).toCovariantSet
    } yield AddingComponentTemplate(name, aliases)

    templateOperation match {
      case Right(operation) => operation
      case Left(msg) => throw RequestSeemsToBeInvalid[PutComponentTemplateAction.Request](msg)
    }
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    // nothing to modify - if it wasn't blocked, we are good
    Modified
  }
}