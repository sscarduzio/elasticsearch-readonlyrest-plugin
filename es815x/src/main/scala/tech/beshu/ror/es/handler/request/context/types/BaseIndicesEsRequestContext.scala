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
import cats.implicits.*
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

abstract class BaseIndicesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                               esContext: EsContext,
                                                               aclContext: AccessControlStaticContext,
                                                               clusterService: RorClusterService,
                                                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty,
    filteredIndices = discoverIndices(),
    allAllowedIndices = Set(ClusterIndexName.Local.wildcard)
  )

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = initialBlockContext.randomNonexistentIndex(_.filteredIndices)
      if (nonExistentIndex.name.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(initialBlockContext.filteredIndices.map(_.randomNonexistentIndex()).toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        update(actionRequest, nonExistingIndices, nonExistingIndices.map(_.name))
      } else {
        ShouldBeInterrupted
      }
    } else {
      val randomNonExistingIndex = initialBlockContext.randomNonexistentIndex(_.filteredIndices)
      update(actionRequest, NonEmptyList.of(randomNonExistingIndex), NonEmptyList.of(randomNonExistingIndex.name))
    }
  }

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    val result = for {
      filteredIndices <- NonEmptyList.fromList(blockContext.filteredIndices.toList)
      allAllowedIndices <- NonEmptyList.fromList(blockContext.allAllowedIndices.toList)
    } yield update(actionRequest, filteredIndices, allAllowedIndices)

    result.getOrElse {
      logger.warn(s"[${id.show}] empty list of indices produced, so we have to interrupt the request processing")
      ShouldBeInterrupted
    }
  }

  protected def requestedIndicesFrom(request: R): Set[RequestedIndex[ClusterIndexName]]

  protected def update(request: R,
                       filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                       allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult

  // todo: do we need it?
//  private def toSortedNonEmptyList[A: Ordering](values: Iterable[A]) = {
//    NonEmptyList.fromList(values.toList.sorted)
//  }

  private def discoverIndices() = {
    val indices = requestedIndicesFrom(actionRequest).orWildcardWhenEmpty
    logger.debug(s"[${id.show}] Discovered indices: ${indices.show}")
    indices
  }
}
