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

import java.util.{List => JList, Map => JMap}

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateRequest, SimulateIndexTemplateResponse}
import org.elasticsearch.cluster.metadata.{Template => EsMetadataTemplate}
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.{IndexName, IndexPattern, TemplateNamePattern}
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class SimulateIndexTemplateRequestEsRequestContext(actionRequest: SimulateIndexTemplateRequest,
                                                   esContext: EsContext,
                                                   aclContext: AccessControlStaticContext,
                                                   clusterService: RorClusterService,
                                                   override val threadPool: ThreadPool)
// note: it may seem that it's template request but it's not. It's rather related with index and that's why we treat it in this way
  extends BaseIndicesEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override lazy val isReadOnlyRequest: Boolean = true

  override protected def indicesFrom(request: SimulateIndexTemplateRequest): Set[IndexName] =
    Option(request.getIndexName)
      .flatMap(NonEmptyString.unapply)
      .map(domain.IndexName.apply)
      .toSet

  override protected def update(request: SimulateIndexTemplateRequest,
                                filteredIndices: NonEmptyList[IndexName],
                                allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    if (filteredIndices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. The whole set of indices [${filteredIndices.toList.mkString(",")}]")
    }
    update(request, filteredIndices.head, allAllowedIndices)
  }

  private def update(request: SimulateIndexTemplateRequest,
                     index: IndexName,
                     allAllowedIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.indexName(index.value.value)
    ModificationResult.UpdateResponse {
      case response: SimulateIndexTemplateResponse =>
        Task.now(SimulateIndexTemplateRequestEsRequestContext.filterAliasesAndIndexPatternsIn(response, allAllowedIndices.toList))
      case other =>
        Task.now(other)
    }
  }
}

object SimulateIndexTemplateRequestEsRequestContext {

  private[types] def filterAliasesAndIndexPatternsIn(response: SimulateIndexTemplateResponse,
                                                     allowedIndices: List[IndexName]): SimulateIndexTemplateResponse = {
    val tunedResponse = new TunedSimulateIndexTemplateResponse(response)
    val filterResponse = filterIndexTemplate(allowedIndices) andThen filterOverlappingTemplates(allowedIndices)
    filterResponse(tunedResponse).underlying
  }

  private def filterIndexTemplate(allowedIndices: List[IndexName]) = (response: TunedSimulateIndexTemplateResponse) => {
    response
      .indexTemplateRequest()
      .map { template =>
        val newTemplate = createMetadataTemplateWithFilteredAliases(
          basedOn = template,
          allowedIndices
        )
        response.indexTemplateRequest(newTemplate)
      }
      .getOrElse {
        response
      }
  }

  private def filterOverlappingTemplates(allowedIndices: List[IndexName]) = (response: TunedSimulateIndexTemplateResponse) => {
    val filteredOverlappingTemplates = createOverlappingTemplatesWithFilteredIndexPatterns(
      basedOn = response.overlappingTemplates(),
      allowedIndices
    )
    response.overlappingTemplates(filteredOverlappingTemplates)
  }

  private def createMetadataTemplateWithFilteredAliases(basedOn: EsMetadataTemplate,
                                                        allowedIndices: List[IndexName]) = {
    val filteredAliases = basedOn
      .aliases().asSafeMap
      .flatMap { case (key, value) => IndexName.fromString(key).map((_, value)) }
      .filterKeys(_.isAllowedBy(allowedIndices.toSet))
      .map { case (key, value) => (key.value.value, value) }
      .asJava
    new EsMetadataTemplate(
      basedOn.settings(),
      basedOn.mappings(),
      filteredAliases
    )
  }

  private def createOverlappingTemplatesWithFilteredIndexPatterns(basedOn: Map[TemplateNamePattern, List[IndexPattern]],
                                                                  allowedIndices: List[IndexName]) = {
    basedOn.flatMap { case (templateName, patterns) =>
      val filteredPatterns = patterns.filter(_.isAllowedByAny(allowedIndices))
      filteredPatterns match {
        case Nil => None
        case _ => Some((templateName, filteredPatterns))
      }
    }
  }

  private[types] class TunedSimulateIndexTemplateResponse(val underlying: SimulateIndexTemplateResponse) {

    private val reflect = on(underlying)
    private val resolvedTemplateFieldName = "resolvedTemplate"
    private val overlappingTemplatesFieldName = "overlappingTemplates"

    def indexTemplateRequest(): Option[EsMetadataTemplate] =
      Option(reflect.get[EsMetadataTemplate](resolvedTemplateFieldName))

    def indexTemplateRequest(template: EsMetadataTemplate): TunedSimulateIndexTemplateResponse = {
      reflect.set(resolvedTemplateFieldName, template)
      this
    }

    def overlappingTemplates(): Map[TemplateNamePattern, List[IndexPattern]] = {
      Option(reflect.get[JMap[String, JList[String]]](overlappingTemplatesFieldName))
        .map(_.asSafeMap)
        .getOrElse(Map.empty)
        .map { case (key, value) =>
          (TemplateNamePattern(NonEmptyString.unsafeFrom(key)), value.asSafeList.flatMap(IndexPattern.fromString))
        }
    }

    def overlappingTemplates(templates: Map[TemplateNamePattern, List[IndexPattern]]): TunedSimulateIndexTemplateResponse = {
      val jTemplatesMap = templates.map { case (key, value) => (key.value.value, value.map(_.value.value).asJava) }.asJava
      reflect.set(overlappingTemplatesFieldName, jTemplatesMap)
      this
    }
  }
}