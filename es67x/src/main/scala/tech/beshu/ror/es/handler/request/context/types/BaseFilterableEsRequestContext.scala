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
import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter, ClusterIndexName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseFilterableEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                  esContext: EsContext,
                                                                  aclContext: AccessControlStaticContext,
                                                                  clusterService: RorClusterService,
                                                                  override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableRequestBlockContext] {

  override val initialBlockContext: FilterableRequestBlockContext = FilterableRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    List.empty,
    {
      import tech.beshu.ror.accesscontrol.show.logs._
      val indices = indicesOrWildcard(indicesFrom(actionRequest))
      logger.debug(s"[${id.show}] Discovered indices: ${indices.map(_.show).mkString(",")}")
      indices
    },
    Set(ClusterIndexName.Local.wildcard),
    None,
    None,
    requestFieldsUsage
  )

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = initialBlockContext.randomNonexistentIndex()
      if (nonExistentIndex.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(initialBlockContext.nonExistingIndicesFromInitialIndices().toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        update(actionRequest, nonExistingIndices, initialBlockContext.filter, initialBlockContext.fieldLevelSecurity)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      update(actionRequest, NonEmptyList.of(initialBlockContext.randomNonexistentIndex()), initialBlockContext.filter, initialBlockContext.fieldLevelSecurity)
      Modified
    }
  }

  override protected def modifyRequest(blockContext: FilterableRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.filteredIndices.toList) match {
      case Some(indices) =>
        update(actionRequest, indices, blockContext.filter, blockContext.fieldLevelSecurity)
      case None =>
        logger.warn(s"[${id.show}] empty list of indices produced, so we have to interrupt the request processing")
        ShouldBeInterrupted
    }
  }

  protected def indicesFrom(request: R): Set[ClusterIndexName]

  protected def update(request: R,
                       indices: NonEmptyList[ClusterIndexName],
                       filter: Option[Filter],
                       fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult

  protected def requestFieldsUsage: RequestFieldsUsage
}