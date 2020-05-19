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
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SearchRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

class SearchEsRequestContext(actionRequest: SearchRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseEsRequestContext[SearchRequestBlockContext](esContext, clusterService)
    with EsRequest[SearchRequestBlockContext] {

  override val initialBlockContext: SearchRequestBlockContext = SearchRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    {
      import tech.beshu.ror.accesscontrol.show.logs._
      val indices = indicesOrWildcard(indicesFrom(actionRequest))
      logger.debug(s"[${id.show}] Discovered indices: ${indices.map(_.show).mkString(",")}")
      indices
    },
    None
  )

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = initialBlockContext.randomNonexistentIndex()
      if (nonExistentIndex.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(initialBlockContext.nonExistingIndicesFromInitialIndices().toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        update(actionRequest, nonExistingIndices, initialBlockContext.filter)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      update(actionRequest, NonEmptyList.of(initialBlockContext.randomNonexistentIndex()), initialBlockContext.filter)
      Modified
    }
  }

  override protected def modifyRequest(blockContext: SearchRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(indices) =>
        update(actionRequest, indices, blockContext.filter)
      case None =>
        logger.warn(s"[${id.show}] empty list of indices produced, so we have to interrupt the request processing")
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: SearchRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  private def update(request: SearchRequest,
                     indices: NonEmptyList[IndexName],
                     filter: Option[Filter]): ModificationResult = {
    optionallyDisableCaching()
    request
      .applyFilterToQuery(filter)
      .indices(indices.toList.map(_.value.value): _*)
    Modified
  }

  //TODO cache is now disabled only when 'fields' rule is used.
  //Remove after 'fields' rule improvements.
  private def optionallyDisableCaching(): Unit = {
    if (esContext.involvesFields) {
      logger.debug("ACL involves fields, will disable request cache for SearchRequest")
      actionRequest.requestCache(false)
    }
  }
}