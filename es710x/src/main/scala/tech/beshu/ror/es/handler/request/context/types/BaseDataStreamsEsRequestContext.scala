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

import cats.implicits.*
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.DataStreamName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

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
    dataStreams = discoverDataStreams(),
    backingIndices = discoverBackingIndices()
  )

  protected def dataStreamsFrom(request: R): Set[DataStreamName]

  protected def backingIndicesFrom(request: R): BackingIndices

  private def discoverDataStreams() = {
    val dataStreams = dataStreamsFrom(actionRequest).orWildcardWhenEmpty
    logger.debug(s"Discovered data streams: ${dataStreams.show}")
    dataStreams
  }

  private def discoverBackingIndices() = {
    val backingIndices = backingIndicesFrom(actionRequest)
    backingIndices match {
      case BackingIndices.IndicesInvolved(filteredIndices, _) =>
        logger.debug(s"Discovered indices: ${filteredIndices.show}")
      case BackingIndices.IndicesNotInvolved =>
    }
    backingIndices
  }
}
