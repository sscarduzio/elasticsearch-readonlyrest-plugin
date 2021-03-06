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
import org.elasticsearch.action.admin.cluster.state.{ClusterStateRequest, ClusterStateResponse}
import org.elasticsearch.cluster.metadata.MetaData
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext.TemplatesTransformation
import tech.beshu.ror.accesscontrol.blocks.rules.utils.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.domain.UriPath.{CatTemplatePath, TemplatePath}
import tech.beshu.ror.accesscontrol.domain.{TemplateNamePattern, UriPath}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

object TemplateClusterStateEsRequestContext {

  def from(actionRequest: ClusterStateRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           settings: Settings,
           threadPool: ThreadPool)
          (implicit generator: UniqueIdentifierGenerator): Option[TemplateClusterStateEsRequestContext] = {
    UriPath.from(esContext.channel.request().uri()) match {
      case Some(TemplatePath(_) | CatTemplatePath(_)) =>
        Some(new TemplateClusterStateEsRequestContext(actionRequest, esContext, clusterService, settings, threadPool))
      case _ =>
        None
    }
  }
}

class TemplateClusterStateEsRequestContext private(actionRequest: ClusterStateRequest,
                                                   esContext: EsContext,
                                                   clusterService: RorClusterService,
                                                   settings: Settings,
                                                   override val threadPool: ThreadPool)
                                                  (implicit generator: UniqueIdentifierGenerator)
  extends BaseTemplatesEsRequestContext[ClusterStateRequest, GettingLegacyTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  private lazy val allTemplatesNamePattern = TemplateNamePattern("*")

  override protected def templateOperationFrom(request: ClusterStateRequest): GettingLegacyTemplates = {
    GettingLegacyTemplates(NonEmptyList.one(allTemplatesNamePattern))
  }

  override def modifyWhenTemplateNotFound: ModificationResult = {
    ModificationResult.UpdateResponse(_ => Task.now(emptyClusterResponse))
  }

  override protected def modifyRequest(blockContext: TemplateRequestBlockContext): ModificationResult = {
    blockContext.templateOperation match {
      case GettingLegacyTemplates(namePatterns) =>
        updateResponse(
          modifyLegacyTemplatesOfResponse(_, namePatterns.toList.toSet, blockContext.responseTemplateTransformation)
        )
      case other =>
        logger.error(
          s"""[${id.show}] Cannot modify templates request because of invalid operation returned by ACL (operation
             | type [${other.getClass}]]. Please report the issue!""".oneLiner)
        ModificationResult.ShouldBeInterrupted
    }
  }

  private def updateResponse(func: ClusterStateResponse => ClusterStateResponse) = {
    ModificationResult.UpdateResponse {
      case response: ClusterStateResponse =>
        Task.now(func(response))
      case other =>
        Task.now(other)
    }
  }

  private def modifyLegacyTemplatesOfResponse(response: ClusterStateResponse,
                                              allowedTemplates: Set[TemplateNamePattern],
                                              transformation: TemplatesTransformation) = {
    implicit val idImplicit: RequestContext.Id = id
    val oldMetadata = response.getState.metaData()
    val filteredTemplates = GetTemplatesEsRequestContext
      .filter(
        oldMetadata.templates().valuesIt().asScala.toList,
        transformation
      )
      .filter { t =>
        TemplateNamePattern
          .fromString(t.name())
          .exists(allowedTemplates.contains)
      }
      .map(_.name())

    val newMetadataWithFilteredTemplates = oldMetadata
      .templates().keysIt().asScala
      .foldLeft(new MetaData.Builder(oldMetadata)) {
        case (acc, templateName) if filteredTemplates.contains(templateName) => acc
        case (acc, templateName) => acc.removeTemplate(templateName)
      }
      .build()

    val modifiedClusterState =
      ClusterState
        .builder(response.getState)
        .metaData(newMetadataWithFilteredTemplates)
        .build()

    new ClusterStateResponse(
      response.getClusterName,
      modifiedClusterState
    )
  }

  private lazy val emptyClusterResponse = {
    new ClusterStateResponse(
      ClusterName.CLUSTER_NAME_SETTING.get(settings), ClusterState.EMPTY_STATE
    )
  }
}
