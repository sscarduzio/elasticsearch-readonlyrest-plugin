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

import cats.data.NonEmptyList
import cats.implicits.*
import org.elasticsearch.action.admin.indices.template.delete.{DeleteComponentTemplateAction, DeleteIndexTemplateRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.DeletingComponentTemplates
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.*

class DeleteComponentTemplateEsRequestContext(actionRequest: DeleteComponentTemplateAction.Request,
                                              esContext: EsContext,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[DeleteComponentTemplateAction.Request, DeletingComponentTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: DeleteComponentTemplateAction.Request): DeletingComponentTemplates = {
    TemplateNamePattern.fromString(request.name()) match {
      case Some(pattern) => DeletingComponentTemplates(NonEmptyList.one(pattern))
      case None => throw RequestSeemsToBeInvalid[DeleteIndexTemplateRequest]("No template name patterns found")
    }
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case DeletingComponentTemplates(namePatterns) =>
        namePatterns.tail match {
          case Nil =>
          case _ =>
            logger.warn(
              s"""[${id.show}] Filtered result contains more than one template pattern. First was taken.
                 | The whole set of patterns [${namePatterns.show}]""".oneLiner)
        }
        actionRequest.name(namePatterns.head.value.value)
        ModificationResult.Modified
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass.show}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }
}
