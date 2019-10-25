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
package tech.beshu.ror.es.request.regular

import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.MetaData
import org.elasticsearch.common.collect.ImmutableOpenMap
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.Outcome
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher.findTemplatesIndicesPatterns
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.domain.UriPath.CatTemplatePath
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.JavaConverters._

class RegularResponseActionListener(baseListener: ActionListener[ActionResponse],
                                    requestContext: RequestContext,
                                    blockContext: BlockContext)
  extends ActionListener[ActionResponse] {

  override def onResponse(response: ActionResponse): Unit = {
    requestContext.uriPath match {
      case CatTemplatePath(_) =>
        baseListener.onResponse(filterTemplatesInClusterStateResponse(response.asInstanceOf[ClusterStateResponse]))
      case _ =>
        baseListener.onResponse(response)
    }
  }

  override def onFailure(e: Exception): Unit = baseListener.onFailure(e)

  // templates are not filtered so we have to do this for our own
  private def filterTemplatesInClusterStateResponse(response: ClusterStateResponse): ClusterStateResponse = {
    val oldMetadata = response.getState.metaData()
    val allowedIndices = blockContext.indices match {
      case Outcome.Exist(indices) if indices.nonEmpty => indices
      case Outcome.Exist(_) | Outcome.NotExist => Set(IndexName.fromUnsafeString("*"))
    }
    val filteredTemplates = oldMetadata
      .templates().valuesIt().asScala.toSet
      .filter { t =>
        findTemplatesIndicesPatterns(
          t.patterns().asScala.flatMap(NonEmptyString.unapply).map(IndexName.apply).toSet,
          allowedIndices
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
      response.getTotalCompressedSize.getBytes,
      response.isWaitForTimedOut
    )
  }
}
