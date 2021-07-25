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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.auto._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.template.get.GetComposableIndexTemplateAction
import org.elasticsearch.cluster.metadata
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.Template.IndexTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingIndexTemplates
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

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
      NonEmptyList.one(TemplateNamePattern("*"))
    }

  override protected def templateOperationFrom(request: GetComposableIndexTemplateAction.Request): GettingIndexTemplates = {
    GettingIndexTemplates(requestTemplateNamePatterns)
  }

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(requestTemplateNamePatterns.head)
    actionRequest.name(nonExistentTemplateNamePattern.value.value)
    ModificationResult.Modified
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingIndexTemplates(namePatterns) =>
        val templateNamePatternToUse =
          if (namePatterns.tail.isEmpty) namePatterns.head
          else TemplateNamePattern.findMostGenericTemplateNamePatten(namePatterns)
        actionRequest.name(templateNamePatternToUse.value.value)
        updateResponse(using = blockContext)
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateResponse(using: TemplateRequestBlockContext) = {
    ModificationResult.UpdateResponse {
      case r: GetComposableIndexTemplateAction.Response =>
        Task.now(new GetComposableIndexTemplateAction.Response(
          GetComposableIndexTemplateEsRequestContext
            .filter(
              templates = r.indexTemplates().asSafeMap,
              using = using.responseTemplateTransformation
            )
            .asJava
        ))
      case other =>
        Task.now(other)
    }
  }

}

private[types] object GetComposableIndexTemplateEsRequestContext extends Logging {

  def filter(templates: Map[String, ComposableIndexTemplate],
             using: Set[Template] => Set[Template])
            (implicit requestContextId: RequestContext.Id): Map[String, ComposableIndexTemplate] = {
    val templatesMap = templates
      .flatMap { case (name, composableIndexTemplate) =>
        toIndexTemplate(name, composableIndexTemplate) match {
          case Right(template) =>
            Some((template, (name, composableIndexTemplate)))
          case Left(msg) =>
            logger.error(
              s"""[${requestContextId.show}] Template response filtering issue: $msg. For security reasons template
                 | [$name] will be skipped.""".oneLiner)
            None
        }
      }
    val filteredTemplates = using(templatesMap.keys.toSet)
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
              logger.error(s"""[${requestContextId.show}] Expected IndexTemplate, but got: $t. Skipping""")
              None
          }
      }
  }

  private def filterMetadataData(composableIndexTemplate: ComposableIndexTemplate, basedOn: IndexTemplate) = {
    new ComposableIndexTemplate(
      basedOn.patterns.toList.map(_.value.stringify).asJava,
      new metadata.Template(
        composableIndexTemplate.template().settings(),
        composableIndexTemplate.template().mappings(),
        filterAliases(composableIndexTemplate.template(), basedOn)
      ),
      composableIndexTemplate.composedOf(),
      composableIndexTemplate.priority(),
      composableIndexTemplate.version(),
      composableIndexTemplate.metadata(),
      composableIndexTemplate.getDataStreamTemplate
    )
  }

  private def filterAliases(template: metadata.Template, basedOn: IndexTemplate) = {
    val aliasesStrings = basedOn.aliases.map(_.stringify)
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
        .fromList(composableIndexTemplate.indexPatterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = Option(composableIndexTemplate.template())
        .map(_.aliases().asSafeMap.keys.flatMap(ClusterIndexName.fromString).toSet)
        .getOrElse(Set.empty)
    } yield IndexTemplate(name, patterns, aliases)
  }
}