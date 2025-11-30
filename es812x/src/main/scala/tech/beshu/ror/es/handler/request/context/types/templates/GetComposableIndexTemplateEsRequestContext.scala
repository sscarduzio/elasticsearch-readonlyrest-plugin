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
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.cluster.metadata
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Template.IndexTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingIndexTemplates
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.jdk.CollectionConverters.*

class GetComposableIndexTemplateEsRequestContext(actionRequest: GetComposableIndexTemplateAction.Request,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
                                                (implicit generator: UniqueIdentifierGenerator)
  extends BaseTemplatesEsRequestContext[GetComposableIndexTemplateAction.Request, GettingIndexTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  private lazy val requestTemplateNamePatterns = NonEmptyList
    .fromList {
      Option(actionRequest.name()).toList
        .flatMap(TemplateNamePattern.fromString)
    }
    .getOrElse {
      NonEmptyList.one(TemplateNamePattern(nes("*")))
    }

  override protected def templateOperationFrom(request: GetComposableIndexTemplateAction.Request): GettingIndexTemplates = {
    GettingIndexTemplates(requestTemplateNamePatterns)
  }

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(requestTemplateNamePatterns.head)
    updateRequest(nonExistentTemplateNamePattern)
    ModificationResult.Modified
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingIndexTemplates(namePatterns) =>
        val templateNamePatternToUse =
          if (namePatterns.tail.isEmpty) namePatterns.head
          else TemplateNamePattern.findMostGenericTemplateNamePatten(namePatterns)
        updateRequest(templateNamePatternToUse)
        updateResponse(`using` = blockContext)
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass.show}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateRequest(templateNamePattern: TemplateNamePattern): Unit = {
    import org.joor.Reflect.*
    on(actionRequest).set("name", templateNamePattern.value.value)
  }

  private def updateResponse(`using`: TemplateRequestBlockContext) = {
    ModificationResult.UpdateResponse.sync {
      case r: GetComposableIndexTemplateAction.Response =>
        new GetComposableIndexTemplateAction.Response(
          GetComposableIndexTemplateEsRequestContext
            .filter(
              templates = r.indexTemplates().asSafeMap,
              usingTemplate = `using`.responseTemplateTransformation
            )
            .asJava
        )
      case other =>
        other
    }
  }

}

private[templates] object GetComposableIndexTemplateEsRequestContext extends RequestIdAwareLogging {

  def filter(templates: Map[String, ComposableIndexTemplate],
             usingTemplate: Set[Template] => Set[Template])
            (implicit requestContextId: RequestContext.Id): Map[String, ComposableIndexTemplate] = {
    val templatesMap = templates
      .flatMap { case (name, composableIndexTemplate) =>
        toIndexTemplate(name, composableIndexTemplate) match {
          case Right(template) =>
            Some((template, (name, composableIndexTemplate)))
          case Left(msg) =>
            logger.error(
              s"""[${requestContextId.show}] Template response filtering issue: ${msg.show}. For security reasons template
                 | [${name.show}] will be skipped.""".oneLiner)
            None
        }
      }
    val filteredTemplates = usingTemplate(templatesMap.keys.toCovariantSet)
    templatesMap
      .flatMap { case (template, (name, composableIndexTemplate)) =>
        filteredTemplates
          .find(_.name == template.name)
          .flatMap {
            case t: IndexTemplate if t == template =>
              Some((name, composableIndexTemplate))
            case t: IndexTemplate =>
              Some((name, filterMetadataData(composableIndexTemplate, t)))
            case t =>
              logger.error(s"""[${requestContextId.show}] Expected IndexTemplate, but got: ${t.show}. Skipping""")
              None
          }
      }
  }

  private def filterMetadataData(composableIndexTemplate: ComposableIndexTemplate, basedOn: IndexTemplate) = {
    ComposableIndexTemplate
      .builder()
      .indexPatterns(basedOn.patterns.map(_.value).stringify.asJava)
      .template(new metadata.Template(
        composableIndexTemplate.template().settings(),
        composableIndexTemplate.template().mappings(),
        filterAliases(composableIndexTemplate.template(), basedOn)
      ))
      .componentTemplates(composableIndexTemplate.composedOf())
      .priority(composableIndexTemplate.priority())
      .version(composableIndexTemplate.version())
      .metadata(composableIndexTemplate.metadata())
      .dataStreamTemplate(composableIndexTemplate.getDataStreamTemplate)
      .allowAutoCreate(composableIndexTemplate.getAllowAutoCreate)
      .ignoreMissingComponentTemplates(composableIndexTemplate.getIgnoreMissingComponentTemplates)
      .deprecated(composableIndexTemplate.isDeprecated)
      .build()
  }

  private def filterAliases(template: metadata.Template, basedOn: IndexTemplate) = {
    val aliasesStrings = basedOn.aliases.stringify
    template
      .aliases().asSafeMap
      .filter { case (name, _) => aliasesStrings.contains(name) }
      .asJava
  }

  private def toIndexTemplate(name: String, composableIndexTemplate: ComposableIndexTemplate) = {
    for {
      name <- TemplateName
        .fromString(name)
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .from(composableIndexTemplate.indexPatterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = Option(composableIndexTemplate.template())
        .map(_.aliases().asSafeMap.keys.flatMap(ClusterIndexName.fromString).toCovariantSet)
        .getOrElse(Set.empty)
    } yield IndexTemplate(name, patterns, aliases)
  }
}