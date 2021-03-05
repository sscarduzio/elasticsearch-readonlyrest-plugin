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

import org.elasticsearch.action.admin.indices.template.put.PutComponentTemplateAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.AddingComponentTemplate
import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class PutComponentTemplateEsRequestContext(actionRequest: PutComponentTemplateAction.Request,
                                           esContext: EsContext,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[PutComponentTemplateAction.Request, AddingComponentTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: PutComponentTemplateAction.Request): AddingComponentTemplate = {
    val templateOperation = for {
      name <- TemplateName
        .fromString(request.name())
        .toRight("Template name should be non-empty")
      aliases = request.componentTemplate().template().aliases().asSafeMap.keys.flatMap(IndexName.fromString).toSet
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