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
import org.elasticsearch.action.admin.indices.template.put.PutComposableIndexTemplateAction
import org.elasticsearch.cluster.metadata.{ComposableIndexTemplate, Template}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.TemplateLike.IndexTemplate
import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class PutComposableIndexTemplateEsRequestContext(actionRequest: PutComposableIndexTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseSingleTemplateEsRequestContext[PutComposableIndexTemplateAction.Request, IndexTemplate](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateFrom(request: PutComposableIndexTemplateAction.Request): IndexTemplate = {
    val templateName = NonEmptyString
      .from(request.name())
      .map(TemplateName.apply)
      .getOrElse(throw invalidRequestException("template name cannot be empty"))

    val indexPatterns = UniqueNonEmptyList
      .fromList(request.indexTemplate().indexPatterns().asScala.flatMap(IndexName.fromString).toList)
      .getOrElse(throw invalidRequestException("is required to have at least one index pattern"))

    val aliases = request.indexTemplate().template().aliases().asSafeMap.values.flatMap(a => IndexName.fromString(a.alias())).toSet

    IndexTemplate(templateName, indexPatterns, aliases)
  }

  override protected def update(request: PutComposableIndexTemplateAction.Request,
                                template: IndexTemplate): ModificationResult = {
    request.indexTemplate(
      filterIndexTemplatePatternsAndAliases(
        request.indexTemplate(),
        template.patterns,
        template.aliases
      )
    )
    ModificationResult.Modified
  }

  private def filterIndexTemplatePatternsAndAliases(indexTemplate: ComposableIndexTemplate,
                                                    allowedPatterns: UniqueNonEmptyList[IndexName],
                                                    allowedAliases: Set[IndexName]) = {
    new ComposableIndexTemplate(
      allowedPatterns.map(_.value.value).toList.asJava,
      filterTemplateAliases(indexTemplate.template(), allowedAliases),
      indexTemplate.composedOf(),
      indexTemplate.priority(),
      indexTemplate.version(),
      indexTemplate.metadata(),
      indexTemplate.getDataStreamTemplate
    )
  }

  private def filterTemplateAliases(template: Template, allowedAliases: Set[IndexName]) = {
    val allowedAliasesStrings = allowedAliases.map(_.value.value)
    new Template(
      template.settings(),
      template.mappings(),
      template
        .aliases().asSafeMap
        .filter { case (_, value) => allowedAliasesStrings.contains(value.alias())}
        .asJava
    )
  }

  private def invalidRequestException(message: String) = {
    RequestSeemsToBeInvalid[PutComposableIndexTemplateAction.Request](s"PutComposableIndexTemplateAction.Request $message")
  }
}
