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

import org.elasticsearch.action.admin.indices.template.put.PutComposableIndexTemplateAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.AddingIndexTemplate
import tech.beshu.ror.accesscontrol.domain.{IndexName, IndexPattern, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class PutComposableIndexTemplateEsRequestContext(actionRequest: PutComposableIndexTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[PutComposableIndexTemplateAction.Request, AddingIndexTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: PutComposableIndexTemplateAction.Request): AddingIndexTemplate = {
    PutComposableIndexTemplateEsRequestContext.templateOperationFrom(request) match {
      case Right(operation) => operation
      case Left(msg) => throw RequestSeemsToBeInvalid[PutComposableIndexTemplateAction.Request](msg)
    }
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    // nothing to modify - if it wasn't blocked, we are good
    Modified
  }
}

object PutComposableIndexTemplateEsRequestContext {

  private [types] def templateOperationFrom(request: PutComposableIndexTemplateAction.Request): Either[String, AddingIndexTemplate] = {
    for {
      name <- TemplateName
        .fromString(request.name())
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .fromList(request.indexTemplate().indexPatterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = request.indexTemplate().template().aliases().asSafeMap.keys.flatMap(IndexName.fromString).toSet
    } yield AddingIndexTemplate(name, patterns, aliases)
  }
}