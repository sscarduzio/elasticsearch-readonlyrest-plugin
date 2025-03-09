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
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest, GetIndexTemplatesResponse}
import org.elasticsearch.cluster.metadata.{AliasMetadata, IndexTemplateMetadata}
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.Template.LegacyTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.BaseTemplatesEsRequestContext
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.jdk.CollectionConverters.*

class GetTemplatesEsRequestContext(actionRequest: GetIndexTemplatesRequest,
                                   esContext: EsContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
                                  (implicit generator: UniqueIdentifierGenerator)
  extends BaseTemplatesEsRequestContext[GetIndexTemplatesRequest, GettingLegacyTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  private lazy val requestTemplateNamePatterns = NonEmptyList
    .fromList(
      actionRequest
        .names().asSafeSet
        .flatMap(TemplateNamePattern.fromString)
        .toList
    )
    .getOrElse(NonEmptyList.one(TemplateNamePattern(nes("*"))))

  override protected def templateOperationFrom(request: GetIndexTemplatesRequest): GettingLegacyTemplates =
    GettingLegacyTemplates(requestTemplateNamePatterns)

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(requestTemplateNamePatterns.head)
    updateRequest(NonEmptyList.one(nonExistentTemplateNamePattern))
    ModificationResult.UpdateResponse(a => Task.delay(a))
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingLegacyTemplates(namePatterns) =>
        updateRequest(namePatterns)
        updateResponse(`using` = blockContext)
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass.show}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateRequest(templateNamePatterns: NonEmptyList[TemplateNamePattern]): Unit = {
    import org.joor.Reflect.*
    on(actionRequest).set("names", templateNamePatterns.map(_.value.value).toList.toArray)
  }

  private def updateResponse(`using`: TemplateRequestBlockContext) = {
    ModificationResult.UpdateResponse {
      case r: GetIndexTemplatesResponse =>
        Task.now(new GetIndexTemplatesResponse(
          GetTemplatesEsRequestContext
            .filter(
              templates = r.getIndexTemplates.asSafeList,
              usingTemplate = `using`.responseTemplateTransformation
            )
            .asJava
        ))
      case other =>
        Task.now(other)
    }
  }

}

private[templates] object GetTemplatesEsRequestContext extends Logging {

  def filter(templates: List[IndexTemplateMetadata],
             usingTemplate: Set[Template] => Set[Template])
            (implicit requestContextId: RequestContext.Id): List[IndexTemplateMetadata] = {
    val templatesMap = templates
      .flatMap { metadata =>
        toLegacyTemplate(metadata) match {
          case Right(template) =>
            Some((template, metadata))
          case Left(msg) =>
            logger.error(
              s"""[${requestContextId.show}] Template response filtering issue: ${msg.show}. For security reasons template
                 | [${metadata.name().show}] will be skipped.""".oneLiner)
            None
        }
      }
      .toMap
    val filteredTemplates = usingTemplate(templatesMap.keys.toCovariantSet)
    templatesMap
      .flatMap { case (template, metadata) =>
        filteredTemplates
          .find(_.name == template.name)
          .flatMap {
            case t: LegacyTemplate if t == template =>
              Some(metadata)
            case t: LegacyTemplate =>
              Some(filterMetadataData(metadata, t))
            case t =>
              logger.error(s"""[${requestContextId.show}] Expected IndexTemplate, but got: ${t.show}. Skipping""")
              None
          }
      }
      .toList
  }

  private def filterMetadataData(metadata: IndexTemplateMetadata, basedOn: LegacyTemplate) = {
    new IndexTemplateMetadata(
      metadata.name(),
      metadata.order(),
      metadata.version(),
      basedOn.patterns.map(_.value).stringify.asJava,
      metadata.settings(),
      ImmutableOpenMapOps.from(Map(metadata.mappings().string() -> metadata.mappings())),
      filterAliases(metadata, basedOn)
    )
  }

  private def filterAliases(metadata: IndexTemplateMetadata, template: LegacyTemplate) = {
    val aliasesStrings = template.aliases.stringify
    val filteredAliasesMap =
      metadata
        .aliases().asSafeValues
        .filter { a => aliasesStrings.contains(a.alias()) }
        .map(a => (a.alias(), a))
        .toMap
    ImmutableOpenMap
      .builder[String, AliasMetadata]()
      .putAllFromMap(filteredAliasesMap.asJava)
      .build()
  }

  private def toLegacyTemplate(metadata: IndexTemplateMetadata) = {
    for {
      name <- TemplateName
        .fromString(metadata.getName)
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .from(metadata.patterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = metadata.aliases().asSafeKeys.flatMap(ClusterIndexName.fromString)
    } yield LegacyTemplate(name, patterns, aliases)
  }
}