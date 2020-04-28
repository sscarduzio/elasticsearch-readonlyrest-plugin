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
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.domain.UriPath.CatIndicesPath
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class ClusterStateEsRequestContext(actionRequest: ClusterStateRequest,
                                   esContext: EsContext,
                                   aclContext: AccessControlStaticContext,
                                   clusterService: RorClusterService,
                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ClusterStateRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ClusterStateRequest): Set[domain.IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: ClusterStateRequest,
                                indices: NonEmptyList[domain.IndexName]): ModificationResult = {

    request.indices(indices.toList.map(_.value.value): _*)
    Modified
  }

  override def modifyWhenIndexNotFound: ModificationResult = {
    uriPath match {
      case CatIndicesPath(_) =>
        update(actionRequest, NonEmptyList.of(initialBlockContext.randomNonexistentIndex()))
      case _ =>
        super.modifyWhenIndexNotFound
    }
  }
}
