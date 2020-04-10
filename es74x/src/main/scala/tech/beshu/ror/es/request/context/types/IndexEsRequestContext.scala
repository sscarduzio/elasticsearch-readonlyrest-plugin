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

import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class IndexEsRequestContext(actionRequest: IndexRequest,
                            esContext: EsContext,
                            aclContext: AccessControlStaticContext,
                            clusterService: RorClusterService,
                            override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indexFrom(request: IndexRequest): IndexName = {
    IndexName
      .fromString(request.index())
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
  }

  override protected def update(request: IndexRequest, index: IndexName): ModificationResult = {
    request.index(index.value.value)
    Modified
  }
}
