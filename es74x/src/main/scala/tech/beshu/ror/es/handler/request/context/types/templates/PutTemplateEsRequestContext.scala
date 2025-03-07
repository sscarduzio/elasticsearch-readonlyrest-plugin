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

import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.AddingLegacyTemplate
import tech.beshu.ror.accesscontrol.domain.{IndexPattern, RequestedIndex, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class PutTemplateEsRequestContext(actionRequest: PutIndexTemplateRequest,
                                  esContext: EsContext,
                                  clusterService: RorClusterService,
                                  override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[PutIndexTemplateRequest, AddingLegacyTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: PutIndexTemplateRequest): AddingLegacyTemplate = {
    val templateOperation = for {
      name <- TemplateName
        .fromString(request.name())
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .from(request.patterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = request.aliases().asSafeSet.flatMap(a => RequestedIndex.fromString(a.name()))
    } yield AddingLegacyTemplate(name, patterns, aliases)

    templateOperation match {
      case Right(operation) => operation
      case Left(msg) => throw RequestSeemsToBeInvalid[PutIndexTemplateRequest](msg)
    }
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    // nothing to modify - if it wasn't blocked, we are good
    Modified
  }
}
