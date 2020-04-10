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
import org.elasticsearch.action.search.MultiSearchRequest
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class MultiSearchEsRequestContext(actionRequest: MultiSearchRequest,
                                  esContext: EsContext,
                                  aclContext: AccessControlStaticContext,
                                  clusterService: RorClusterService,
                                  override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[MultiSearchRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: MultiSearchRequest): Set[IndexName] = {
    request.requests().asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
  }

  override protected def update(request: MultiSearchRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    optionallyDisableCaching()
    modifyIndicesOf(request, indices)
  }

  // Cache disabling for this request is crucial for document level security to work.
  // Otherwise we'd get an answer from the cache some times and would not be filtered
  private def optionallyDisableCaching(): Unit = {
    if (esContext.involveFilters) {
      logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
      actionRequest.requests().asScala.foreach(_.requestCache(false))
    }
  }

  private def modifyIndicesOf(request: MultiSearchRequest,
                              nelOfIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.requests().asScala.foreach { sr =>
      if (sr.indices.asSafeSet.isEmpty || sr.indices.asSafeSet.contains("*")) {
        sr.indices(nelOfIndices.toList.map(_.value.value): _*)
      } else {
        // This transforms wildcards and aliases in concrete indices
        val expandedSrIndices = clusterService.expandIndices(sr.indices().asSafeSet.flatMap(IndexName.fromString))
        val remaining = expandedSrIndices.intersect(nelOfIndices.toList.toSet)

        if (remaining.isEmpty) { // contained just forbidden indices, should return zero results
          sr.source(new SearchSourceBuilder().size(0))
        } else if (remaining.size == expandedSrIndices.size) { // contained all allowed indices
          // nothing to do
        } else {
          // some allowed indices were there, restrict query to those
          sr.indices(remaining.toList.map(_.value.value): _*)
        }
      }
    }
    if (request.requests().asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }
}
