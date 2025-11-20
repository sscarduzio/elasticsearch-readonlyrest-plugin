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
import org.elasticsearch.action.admin.indices.template.get.GetComponentTemplateAction
import org.elasticsearch.cluster.metadata
import org.elasticsearch.cluster.metadata.ComponentTemplate as MetadataComponentTemplate
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.Template.ComponentTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingComponentTemplates
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, Template, TemplateName, TemplateNamePattern}
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class GetComponentTemplateEsRequestContext(actionRequest: GetComponentTemplateAction.Request,
                                           esContext: EsContext,
                                           override val threadPool: ThreadPool)
                                          (implicit generator: UniqueIdentifierGenerator)
  extends BaseTemplatesEsRequestContext[GetComponentTemplateAction.Request, GettingComponentTemplates](
    actionRequest, esContext, threadPool
  ) {

  private lazy val requestTemplateNamePatterns = NonEmptyList
    .fromList {
      Option(actionRequest.name()).toList
        .flatMap(TemplateNamePattern.fromString)
    }
    .getOrElse {
      NonEmptyList.one(TemplateNamePattern(nes("*")))
    }

  override protected def templateOperationFrom(request: GetComponentTemplateAction.Request): GettingComponentTemplates = {
    GettingComponentTemplates(requestTemplateNamePatterns)
  }

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(requestTemplateNamePatterns.head)
    actionRequest.name(nonExistentTemplateNamePattern.value.value)
    ModificationResult.Modified
  }

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingComponentTemplates(namePatterns) =>
        if(namePatterns.tail.nonEmpty) {
          logger.warn(
            s"""[${id.show}] Filtered result contains more than one template. First was taken. The whole set of
               | component templates [${namePatterns.show}]""".stripMargin)
        }
        actionRequest.name(namePatterns.head.value.value)
        updateResponse(`using` = blockContext)
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass.show}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateResponse(`using`: TemplateRequestBlockContext) = {
    ModificationResult.UpdateResponse.sync {
      case r: GetComponentTemplateAction.Response =>
        new GetComponentTemplateAction.Response(
          filter(
            templates = r.getComponentTemplates.asSafeMap,
            usingTemplate = `using`.responseTemplateTransformation
          ),
          r.getRolloverConfiguration
        )
      case other =>
        other
    }
  }

  private def filter(templates: Map[String, MetadataComponentTemplate],
                     usingTemplate: Set[Template] => Set[Template]) = {
    val templatesMap = templates
      .flatMap { case (name, componentTemplate) =>
        toComponentTemplate(name, componentTemplate) match {
          case Right(template) =>
            Some((template, (name, componentTemplate)))
          case Left(msg) =>
            logger.error(
              s"""[${id.show}] Component Template response filtering issue: ${msg.show}. For security reasons template
                 | [${name.show}] will be skipped.""".oneLiner)
            None
        }
      }
    val filteredTemplates = usingTemplate(templatesMap.keys.toCovariantSet)
    templatesMap
      .flatMap { case (template, (name, componentTemplate)) =>
        filteredTemplates
          .find(_.name.value.value == name)
          .flatMap {
            case t: ComponentTemplate if t == template =>
              Some((name, componentTemplate))
            case t: ComponentTemplate =>
              Some((name, filterMetadataData(componentTemplate, t)))
            case t =>
              logger.error(s"""[${id.show}] Expected ComponentTemplate, but got: ${t.show}. Skipping""")
              None
          }
      }
      .asJava
  }

  private def filterMetadataData(componentTemplate: MetadataComponentTemplate, basedOn: ComponentTemplate) = {
    new MetadataComponentTemplate(
      new metadata.Template(
        componentTemplate.template.settings,
        componentTemplate.template.mappings,
        filterAliases(componentTemplate.template, basedOn)
      ),
      componentTemplate.version(),
      componentTemplate.metadata()
    )
  }

  private def filterAliases(template: metadata.Template, basedOn: ComponentTemplate) = {
    val aliasesStrings = basedOn.aliases.stringify
    template
      .aliases().asSafeMap
      .filter { case (name, _) => aliasesStrings.contains(name) }
      .asJava
  }

  private def toComponentTemplate(name: String, componentTemplate: MetadataComponentTemplate) = {
    for {
      name <- TemplateName
        .fromString(name)
        .toRight("Template name should be non-empty")
      aliases = Option(componentTemplate.template())
        .map(_.aliases().asSafeMap.keys.flatMap(ClusterIndexName.fromString).toCovariantSet)
        .getOrElse(Set.empty)
    } yield ComponentTemplate(name, aliases)
  }
}
