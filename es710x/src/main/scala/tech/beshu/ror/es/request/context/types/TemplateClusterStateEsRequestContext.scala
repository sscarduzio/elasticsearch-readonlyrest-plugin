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
import cats.implicits._
import eu.timepit.refined.auto._
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.admin.cluster.state.{ClusterStateRequest, ClusterStateResponse}
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.Metadata
import org.elasticsearch.common.collect.ImmutableOpenMap
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.domain.UriPath.{CatTemplatePath, TemplatePath}
import tech.beshu.ror.accesscontrol.domain.{TemplateNamePattern, UriPath}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

object TemplateClusterStateEsRequestContext {

  def from(actionRequest: ClusterStateRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[TemplateClusterStateEsRequestContext] = {
    UriPath.from(esContext.channel.request().uri()) match {
      case Some(TemplatePath(_) | CatTemplatePath(_)) =>
        Some(new TemplateClusterStateEsRequestContext(actionRequest, esContext, clusterService, threadPool))
      case _ =>
        None
    }
  }
}

class TemplateClusterStateEsRequestContext private(actionRequest: ClusterStateRequest,
                                                   esContext: EsContext,
                                                   clusterService: RorClusterService,
                                                   override val threadPool: ThreadPool)
  extends BaseTemplatesEsRequestContext[ClusterStateRequest, GettingLegacyTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(request: ClusterStateRequest): GettingLegacyTemplates = {
    GettingLegacyTemplates(NonEmptyList.one(TemplateNamePattern("*")))
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingLegacyTemplates(namePatterns) =>
        ModificationResult.UpdateResponse(
          updateCatTemplateResponse(namePatterns.toList.toSet)
        )
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateCatTemplateResponse(allowedTemplates: Set[TemplateNamePattern])
                                       (actionResponse: ActionResponse): Task[ActionResponse] = Task.now {
    actionResponse match {
      case response: ClusterStateResponse =>
        val oldMetadata = response.getState.metadata()
        val filteredTemplates = oldMetadata
          .templates().valuesIt().asScala.toSet
          .filter { t =>
            // todo: here is template name and we want to check if tempalte pattern applies to it
            TemplateNamePattern
              .fromString(t.name())
              .exists(allowedTemplates.contains)
          }

        val newMetadataWithFilteredTemplates = oldMetadata.templates().valuesIt().asScala
          .foldLeft(new Metadata.Builder(oldMetadata)) {
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
            .metadata(newMetadataWithFilteredTemplates)
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
