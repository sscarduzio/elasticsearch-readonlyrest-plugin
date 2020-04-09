package tech.beshu.ror.es.request.context.types

import java.util.UUID

import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.utils.ClusterServiceHelper._
import tech.beshu.ror.utils.ScalaOps._

class GetIndexTemplatesEsRequestContext(actionRequest: GetIndexTemplatesRequest,
                                        esContext: EsContext,
                                        clusterService: RorClusterService,
                                        override val threadPool: ThreadPool)
  extends BaseEsRequestContext[TemplateRequestBlockContext](esContext, clusterService)
    with EsRequest[TemplateRequestBlockContext] {

  private val originIndicesPatterns = indicesPatternsFrom(actionRequest)

  override val initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    originIndicesPatterns
  )

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.indices.toList match {
      case Nil =>
        ShouldBeInterrupted
      case nel =>
        update(actionRequest, nel.toSet)
        Modified
    }
  }

  private def indicesPatternsFrom(request: GetIndexTemplatesRequest): Set[IndexName] = {
    val templates = actionRequest.names.asSafeSet
    val patterns =
      if (templates.isEmpty) getIndicesPatternsOfTemplates(clusterService)
      else getIndicesPatternsOfTemplates(clusterService, templates)
    if (patterns.nonEmpty) patterns else Set(IndexName.wildcard)
  }

  private def update(request: GetIndexTemplatesRequest, filteredIndices: Set[IndexName]): Unit = {
    val requestTemplateNames = request.names.asSafeSet
    val allowedTemplateNames = clusterService.findTemplatesOfIndices(filteredIndices)
    val templateNamesToReturn =
      if (requestTemplateNames.isEmpty) {
        allowedTemplateNames
      } else {
        MatcherWithWildcardsScalaAdapter
          .create(requestTemplateNames)
          .filter(allowedTemplateNames)
      }
    if (templateNamesToReturn.isEmpty) {
      // hack! there is no other way to return empty list of templates (at the moment should not be used, but
      // I leave it as a protection
      request.names(UUID.randomUUID + "*")
    } else {
      request.names(templateNamesToReturn.map(_.value.value).toList: _*)
    }
  }
}
