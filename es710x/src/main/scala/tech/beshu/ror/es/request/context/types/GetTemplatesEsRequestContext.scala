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
import monix.eval.Task
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest, GetIndexTemplatesResponse}
import org.elasticsearch.cluster.metadata.IndexTemplateMetadata
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.Template.LegacyTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template, TemplateName, TemplateNamePattern}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

class GetTemplatesEsRequestContext(actionRequest: GetIndexTemplatesRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[GetIndexTemplatesRequest, GettingLegacyTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: GetIndexTemplatesRequest): GettingLegacyTemplates = {
    val templateNamePatterns = request
      .names().asSafeSet
      .flatMap(TemplateNamePattern.fromString)
      .toList
    NonEmptyList.fromList(templateNamePatterns) match {
      case Some(patterns) => GettingLegacyTemplates(patterns)
      case None => throw RequestSeemsToBeInvalid[GetIndexTemplatesRequest]("No template name patterns found")
    }
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    ModificationResult.UpdateResponse {
      case r: GetIndexTemplatesResponse =>
        Task.now(new GetIndexTemplatesResponse(
          filter(
            templates = r.getIndexTemplates.asSafeList,
            using = blockContext.responseTemplateTransformation
          )
        ))
      case other =>
        Task.now(other)
    }
  }

  private def filter(templates: List[IndexTemplateMetadata],
                     using: Set[Template] => Set[Template]) = {
    val templatesMap = templates
      .flatMap { metadata =>
        toLegacyTemplate(metadata) match {
          case Right(template) =>
            Some((template, metadata))
          case Left(msg) =>
            logger.error(
              s"""[${id.show}] Template response filtering issue: $msg. For security reasons template
                 | [${metadata.name()}] will be skipped.""".oneLiner)
            None
        }
      }
      .toMap
    val filteredTemplates = using(templatesMap.keys)
    templatesMap
      .filterKeys(filteredTemplates.contains)
      .values
      .toList
      .asJava
  }

  private def toLegacyTemplate(metadata: IndexTemplateMetadata) = {
    for {
      name <- TemplateName
        .fromString(metadata.getName)
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .fromList(metadata.patterns().asSafeList.flatMap(IndexName.fromString))
        .toRight("Template indices pattern list should not be empty")
    } yield LegacyTemplate(name, patterns)
  }
}
