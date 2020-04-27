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
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseIndicesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                               esContext: EsContext,
                                                               aclContext: AccessControlStaticContext,
                                                               clusterService: RorClusterService,
                                                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    {
      import tech.beshu.ror.accesscontrol.show.logs._
      val indices = indicesOrWildcard(indicesFrom(actionRequest))
      logger.debug(s"[${id.show}] Discovered indices: ${indices.map(_.show).mkString(",")}")
      indices
    }
  )

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = randomNonexistentIndex()
      if (nonExistentIndex.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(nonExistingIndicesFromInitialIndices().toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        update(actionRequest, nonExistingIndices)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      update(actionRequest, NonEmptyList.of(randomNonexistentIndex()))
      Modified
    }
  }

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(indices) =>
        update(actionRequest, indices)
      case None =>
        logger.warn(s"[${id.show}] empty list of indices produced, so we have to interrupt the request processing")
        ShouldBeInterrupted
    }
  }

  protected def indicesFrom(request: R): Set[IndexName]

  protected def update(request: R, indices: NonEmptyList[IndexName]): ModificationResult

  protected def randomNonexistentIndex(): IndexName = {
    val localIndices = initialBlockContext.indices.filterNot(_.isClusterIndex)
    val someIndexFromRequest = localIndices.find(_.hasWildcard) orElse localIndices.headOption
    someIndexFromRequest match {
      case Some(indexName) => IndexName.randomNonexistentIndex(indexName.value.value)
      case None => IndexName.randomNonexistentIndex()
    }
  }

  private def nonExistingIndicesFromInitialIndices(): Set[IndexName] = {
    initialBlockContext.indices.map(i => IndexName.randomNonexistentIndex(i.value.value))
  }

}
