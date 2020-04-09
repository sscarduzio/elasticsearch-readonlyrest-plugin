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

import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.utils.ClusterServiceHelper.indicesFromPatterns

import scala.collection.JavaConverters._

class CreateTemplateEsRequestContext(actionRequest: PutIndexTemplateRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseEsRequestContext[TemplateRequestBlockContext](esContext, clusterService)
    with EsRequest[TemplateRequestBlockContext] {

  override val initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indicesPatternsFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.indices.toList match {
      case Nil =>
        ModificationResult.ShouldBeInterrupted
      case nel =>
        updateIndicesPatterns(actionRequest, nel)
        Modified
    }
  }

  private def indicesPatternsFrom(request: PutIndexTemplateRequest): Set[domain.IndexName] = {
    indicesFromPatterns(clusterService, request.patterns().asScala.flatMap(IndexName.fromString).toSet)
      .flatMap { case (pattern, relatedIndices) =>
        if (relatedIndices.nonEmpty) relatedIndices
        else Set(pattern)
      }
      .toSet
  }

  private def updateIndicesPatterns(request: PutIndexTemplateRequest, indices: List[IndexName]) = {
    request.patterns(indices.map(_.value.value).asJava)
  }

  // todo:
  //  private def templatesFrom(request: PutIndexTemplateRequest) = Set {
  //    val template = for {
  //      templateName <- NonEmptyString.unapply(request.name())
  //      indexPatterns <- request.indices
  //        .asSafeSet
  //        .flatMap(IndexPattern.from)
  //        .toNonEmptySet
  //    } yield Template(templateName, indexPatterns)
  //    template.getOrElse(throw RequestSeemsToBeInvalid[PutIndexTemplateRequest]("Template data is invalid"))
  //  }
}
