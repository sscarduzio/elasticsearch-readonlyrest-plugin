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
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest, GetIndexTemplatesResponse}
import org.elasticsearch.cluster.metadata.{AliasMetaData, IndexTemplateMetaData}
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.onClass
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.domain.Template.LegacyTemplate
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils._

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
    .getOrElse(NonEmptyList.one(TemplateNamePattern("*")))

  override protected def templateOperationFrom(request: GetIndexTemplatesRequest): GettingLegacyTemplates =
    GettingLegacyTemplates(requestTemplateNamePatterns)

  override def modifyWhenTemplateNotFound: ModificationResult = {
    val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(requestTemplateNamePatterns.head)
    actionRequest.names(nonExistentTemplateNamePattern.value.value)
    ModificationResult.Modified
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingLegacyTemplates(namePatterns) =>
        actionRequest.names(namePatterns.map(_.value.value).toList: _*)
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
      case r: GetIndexTemplatesResponse =>
        Task.now(newGetIndexTemplatesResponse(
          GetTemplatesEsRequestContext
            .filter(
              templates = r.getIndexTemplates.asSafeList,
              using = using.responseTemplateTransformation
            )
        ))
      case other =>
        Task.now(other)
    }
  }

  private def newGetIndexTemplatesResponse(templates: List[IndexTemplateMetaData]) = {
    onClass(classOf[GetIndexTemplatesResponse])
      .create(templates.asJava)
      .get[GetIndexTemplatesResponse]()
  }
}

private[types] object GetTemplatesEsRequestContext extends Logging {

  def filter(templates: List[IndexTemplateMetaData],
             using: Set[Template] => Set[Template])
            (implicit requestContextId: RequestContext.Id): List[IndexTemplateMetaData] = {
    val templatesMap = templates
      .flatMap { metadata =>
        toLegacyTemplate(metadata) match {
          case Right(template) =>
            Some((template, metadata))
          case Left(msg) =>
            logger.error(
              s"""[${requestContextId.show}] Template response filtering issue: $msg. For security reasons template
                 | [${metadata.name()}] will be skipped.""".oneLiner)
            None
        }
      }
      .toMap
    val filteredTemplates = using(templatesMap.keys.toSet)
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
              logger.error(s"""[${requestContextId.show}] Expected IndexTemplate, but got: $t. Skipping""")
              None
          }
      }
      .toList
  }

  private def filterMetadataData(metadata: IndexTemplateMetaData, basedOn: LegacyTemplate) = {
    new IndexTemplateMetaData(
      metadata.name(),
      metadata.order(),
      metadata.version(),
      basedOn.patterns.toList.map(_.value.stringify).asJava,
      metadata.settings(),
      metadata.mappings(),
      filterAliases(metadata, basedOn),
      metadata.customs()
    )
  }

  private def filterAliases(metadata: IndexTemplateMetaData, template: LegacyTemplate) = {
    val aliasesStrings = template.aliases.map(_.stringify)
    val filteredAliasesMap =
      metadata
        .aliases().asSafeValues
        .filter { a => aliasesStrings.contains(a.alias()) }
        .map(a => (a.alias(), a))
        .toMap
    new ImmutableOpenMap.Builder[String, AliasMetaData]()
      .putAll(filteredAliasesMap.asJava)
      .build()
  }

  private def toLegacyTemplate(metadata: IndexTemplateMetaData) = {
    for {
      name <- TemplateName
        .fromString(metadata.getName)
        .toRight("Template name should be non-empty")
      patterns <- UniqueNonEmptyList
        .fromTraversable(metadata.patterns().asSafeList.flatMap(IndexPattern.fromString))
        .toRight("Template indices pattern list should not be empty")
      aliases = metadata.aliases().asSafeKeys.flatMap(ClusterIndexName.fromString)
    } yield LegacyTemplate(name, patterns, aliases)
  }
}