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

import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified

class IndexEsRequestContext(actionRequest: IndexRequest,
                            esContext: EsContext,
                            aclContext: AccessControlStaticContext,
                            override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext(actionRequest, esContext, aclContext, threadPool) {

  override protected def requestedIndexFrom(request: IndexRequest): RequestedIndex[ClusterIndexName] = {
    RequestedIndex
      .fromString(request.index())
      .getOrElse(throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid"))
  }

  override protected def update(request: IndexRequest, index: RequestedIndex[ClusterIndexName]): ModificationResult = {
    request.index(index.stringify)
    Modified
  }
}
