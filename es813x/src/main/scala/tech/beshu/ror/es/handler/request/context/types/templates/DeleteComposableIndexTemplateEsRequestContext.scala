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
import cats.implicits._
import org.elasticsearch.Version
import org.elasticsearch.action.admin.indices.template.delete.{DeleteIndexTemplateRequest, TransportDeleteComposableIndexTemplateAction}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.DeletingIndexTemplates
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.utils.ScalaOps._

class DeleteComposableIndexTemplateEsRequestContext(actionRequest: TransportDeleteComposableIndexTemplateAction.Request,
                                                    esContext: EsContext,
                                                    clusterService: RorClusterService,
                                                    override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[TransportDeleteComposableIndexTemplateAction.Request, DeletingIndexTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: TransportDeleteComposableIndexTemplateAction.Request): DeletingIndexTemplates = {
    NonEmptyList.fromList(request.getNames) match {
      case Some(patterns) => DeletingIndexTemplates(patterns)
      case None => throw RequestSeemsToBeInvalid[DeleteIndexTemplateRequest]("No template name patterns found")
    }
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case DeletingIndexTemplates(namePatterns) =>
        actionRequest.updateNames(namePatterns)
        ModificationResult.Modified
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  implicit class DeleteComposableIndexTemplateActionRequestOps(request: TransportDeleteComposableIndexTemplateAction.Request) {

    def getNames: List[TemplateNamePattern] = {
      if (isEsNewerThan712) getNamesForEsPost12
      else getNamesForEsPre13
    }

    def updateNames(names: NonEmptyList[TemplateNamePattern]): Unit = {
      if (isEsNewerThan712) updateNamesForEsPost12(names)
      else updateNamesForEsPre13(names)
    }

    private def isEsNewerThan712 = {
      Version.CURRENT.after(Version.fromString("7.12.1"))
    }

    private def getNamesForEsPre13 = {
      Option(on(request).call("name").get[String]).toList
        .flatMap(TemplateNamePattern.fromString)
    }

    private def getNamesForEsPost12 = {
      on(request)
        .call("names")
        .get[Array[String]]
        .asSafeList
        .flatMap(TemplateNamePattern.fromString)
    }

    private def updateNamesForEsPre13(names: NonEmptyList[TemplateNamePattern]) = {
      names.tail match {
        case Nil =>
        case _ =>
          logger.warn(
            s"""[${id.show}] Filtered result contains more than one template pattern. First was taken.
               | The whole set of patterns [${names.toList.mkString(",")}]""".oneLiner)
      }
      on(request).call("name", names.head.value.value)
    }

    private def updateNamesForEsPost12(names: NonEmptyList[TemplateNamePattern]): Unit = {
      on(request).set("names", names.toList.map(_.value.value).toArray)
    }
  }
}
