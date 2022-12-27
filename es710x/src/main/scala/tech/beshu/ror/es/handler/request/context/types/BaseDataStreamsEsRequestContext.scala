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

import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest}

abstract class BaseDataStreamsEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                   esContext: EsContext,
                                                                   clusterService: RorClusterService,
                                                                   override val threadPool: ThreadPool)
  extends BaseEsRequestContext[DataStreamRequestBlockContext](esContext, clusterService)
    with EsRequest[DataStreamRequestBlockContext] {

  override val initialBlockContext: DataStreamRequestBlockContext = DataStreamRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty,
    dataStreams = {
      val dataStreams = dataStreamsOrWildcard(dataStreamsFrom(actionRequest))
      logger.debug(s"[${id.show}] Discovered data streams: ${dataStreams.map(_.show).mkString(",")}")
      dataStreams
    },
    backingIndices = {
      val backingIndices = backingIndicesFrom(actionRequest)
      backingIndices match {
        case BackingIndices.IndicesInvolved(filteredIndices, allAllowedIndices) =>
          logger.debug(s"[${id.show}] Discovered indices: ${filteredIndices.map(_.show).mkString(",")}")
        case BackingIndices.IndicesNotInvolved =>
      }
      backingIndices
    },
  )

  protected def dataStreamsFrom(request: R): Set[DataStreamName]

  protected def backingIndicesFrom(request: R): BackingIndices

}
