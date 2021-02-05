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

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.auto._
import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingIndexTemplates
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class GetComposableIndexTemplateEsRequestContext(actionRequest: GetComposableIndexTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[GetComposableIndexTemplateAction.Request, GettingIndexTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: GetComposableIndexTemplateAction.Request): GettingIndexTemplates = {
    GettingIndexTemplates(NonEmptyList.one(
      Option(request.name())
        .flatMap(TemplateNamePattern.fromString)
        .getOrElse(TemplateNamePattern("*"))
    ))
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingIndexTemplates(namePatterns) =>
        namePatterns.tail match {
          case Nil =>
          case _ =>
            logger.warn(
              s"""[${id.show}] Filtered result contains more than one template pattern. First was taken.
                 | Whole set of patterns [${namePatterns.toList.mkString(",")}]""".oneLiner)
        }
        actionRequest.name(namePatterns.head.value.value)
        ModificationResult.Modified
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }
}
