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
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.cluster.state.{ClusterStateRequest, ClusterStateResponse}
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.MetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher.narrowAllowedTemplatesIndicesPatterns
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.domain.UriPath.{CatTemplatePath, TemplatePath}
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class ClusterStateEsRequestContext(actionRequest: ClusterStateRequest,
                                   esContext: EsContext,
                                   aclContext: AccessControlStaticContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ClusterStateRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterStateRequest): Set[domain.IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: ClusterStateRequest,
                                indices: NonEmptyList[domain.IndexName]): ModificationResult = {
    uriPath match {
      case TemplatePath(_) | CatTemplatePath(_) =>
        ModificationResult.UpdateResponse(updateCatTemplateResponse(indices))
      case _ =>
        request.indices(indices.toList.map(_.value.value): _*)
        Modified
    }
  }

  private def updateCatTemplateResponse(allowedIndices: NonEmptyList[domain.IndexName])
                                       (actionResponse: ActionResponse): ActionResponse = {
    actionResponse match {
      case response: ClusterStateResponse =>
        val oldMetadata = response.getState.metaData()
        val filteredTemplates = oldMetadata
          .templates().valuesIt().asScala.toSet
          .filter { t =>
            narrowAllowedTemplatesIndicesPatterns(
              t.patterns().asScala.flatMap(NonEmptyString.unapply).map(IndexName.apply).toSet,
              allowedIndices.toList.toSet
            ).nonEmpty
          }

        val newMetadataWithFilteredTemplates = oldMetadata.templates().valuesIt().asScala
          .foldLeft(new MetaData.Builder(oldMetadata)) {
            case (acc, elem) => acc.removeTemplate(elem.name())
          }
          .templates(
            ImmutableOpenMap
              .builder(filteredTemplates.size)
              .putAll(filteredTemplates.map(t => (t.name(), t)).toMap.asJava)
              .build()
          )
          .build()

        val modifiedClusterState =
          ClusterState
            .builder(response.getState)
            .metaData(newMetadataWithFilteredTemplates)
            .build()

        new ClusterStateResponse(
          response.getClusterName,
          modifiedClusterState,
          response.isWaitForTimedOut
        )
      case response => response
    }
  }

}
