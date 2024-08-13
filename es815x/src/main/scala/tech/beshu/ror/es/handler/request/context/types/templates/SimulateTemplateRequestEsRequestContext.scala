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
import monix.eval.Task
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateResponse, SimulateTemplateAction}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{AddingIndexTemplateAndGetAllowedOnes, GettingIndexTemplates}
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, TemplateNamePattern, TemplateOperation}
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.es.handler.request.context.types.templates.SimulateIndexTemplateRequestEsRequestContext.TunedSimulateIndexTemplateResponse
import tech.beshu.ror.utils.ScalaOps._

object SimulateTemplateRequestEsRequestContext {
  def from(actionRequest: SimulateTemplateAction.Request,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool)
          (implicit generator: UniqueIdentifierGenerator): SimulateTemplateRequestEsRequestContext[_ <: TemplateOperation] = {
    Option(actionRequest.getTemplateName).flatMap(TemplateNamePattern.fromString) match {
      case Some(templateName) =>
        new SimulateExistingTemplateRequestEsRequestContext(templateName, actionRequest, esContext, clusterService, threadPool)
      case None =>
        new SimulateNewTemplateRequestEsRequestContext(actionRequest, esContext, clusterService, threadPool)
    }
  }
}

class SimulateNewTemplateRequestEsRequestContext(actionRequest: SimulateTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends SimulateTemplateRequestEsRequestContext[AddingIndexTemplateAndGetAllowedOnes](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(actionRequest: SimulateTemplateAction.Request): AddingIndexTemplateAndGetAllowedOnes = {
    Option(actionRequest.getIndexTemplateRequest)
      .map { newTemplateRequest =>
        PutComposableIndexTemplateEsRequestContext.templateOperationFrom(newTemplateRequest) match {
          case Right(operation) => AddingIndexTemplateAndGetAllowedOnes(
            operation.name, operation.patterns, operation.aliases, List(TemplateNamePattern.wildcard)
          )
          case Left(msg) =>
            throw RequestSeemsToBeInvalid[SimulateTemplateAction.Request](msg)
        }
      }
      .getOrElse {
        throw RequestSeemsToBeInvalid[SimulateTemplateAction.Request]("Index template definition doesn't exist")
      }
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case AddingIndexTemplateAndGetAllowedOnes(_, _, _, allowedTemplates) =>
        updateResponse(allowedTemplates, blockContext.allAllowedIndices.toList)
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }
}

class SimulateExistingTemplateRequestEsRequestContext(existingTemplateName: TemplateNamePattern,
                                                      actionRequest: SimulateTemplateAction.Request,
                                                      esContext: EsContext,
                                                      clusterService: RorClusterService,
                                                      override val threadPool: ThreadPool)
                                                     (implicit generator: UniqueIdentifierGenerator)
  extends SimulateTemplateRequestEsRequestContext[GettingIndexTemplates](actionRequest, esContext, clusterService, threadPool) {

  override protected def templateOperationFrom(actionRequest: SimulateTemplateAction.Request): GettingIndexTemplates =
    GettingIndexTemplates(NonEmptyList.of(existingTemplateName))

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(existingTemplateName)
    actionRequest.templateName(nonExistentTemplateNamePattern.value.value)
    ModificationResult.Modified
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingIndexTemplates(namePatterns) =>
        namePatterns.find(_ == existingTemplateName) match {
          case Some(_) =>
            updateResponse(namePatterns.toList, blockContext.allAllowedIndices.toList)
          case None =>
            logger.info(s"[${id.show}] User has no access to template ${existingTemplateName.value.value}")
            ModificationResult.ShouldBeInterrupted
        }
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }
}

abstract class SimulateTemplateRequestEsRequestContext[O <: TemplateOperation](actionRequest: SimulateTemplateAction.Request,
                                                                               esContext: EsContext,
                                                                               clusterService: RorClusterService,
                                                                               override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[SimulateTemplateAction.Request, O](actionRequest, esContext, clusterService, threadPool) {

  protected def updateResponse(allowedTemplates: List[TemplateNamePattern],
                               allowedIndices: List[ClusterIndexName]): ModificationResult.UpdateResponse = {
    ModificationResult.UpdateResponse {
      case response: SimulateIndexTemplateResponse =>
        Task.now(filterTemplatesIn(response, allowedTemplates, allowedIndices))
      case other =>
        Task.now(other)
    }
  }

  private def filterTemplatesIn(response: SimulateIndexTemplateResponse,
                                allowedTemplates: List[TemplateNamePattern],
                                allowedIndices: List[ClusterIndexName]): SimulateIndexTemplateResponse = {
    val tunedResponse = new TunedSimulateIndexTemplateResponse(response)
    val filterResponse = filterOverlappingTemplates(allowedTemplates) andThen filterAliasesAndIndexPatternsIn(allowedIndices)
    filterResponse(tunedResponse).underlying
  }

  private def filterOverlappingTemplates(templates: List[TemplateNamePattern]) = (response: TunedSimulateIndexTemplateResponse) => {
    val filteredOverlappingTemplates = response
      .overlappingTemplates()
      .filter { case (key, _) => templates.contains(key) }
    response.overlappingTemplates(filteredOverlappingTemplates)
  }

  private def filterAliasesAndIndexPatternsIn(allowedIndices: List[ClusterIndexName]) = (response: TunedSimulateIndexTemplateResponse) => {
    new TunedSimulateIndexTemplateResponse(
      SimulateIndexTemplateRequestEsRequestContext.filterAliasesAndIndexPatternsIn(response.underlying, allowedIndices)
    )
  }
}
