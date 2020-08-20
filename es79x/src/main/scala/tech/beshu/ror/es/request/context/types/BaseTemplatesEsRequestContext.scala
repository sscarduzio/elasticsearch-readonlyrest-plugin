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

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}

abstract class BaseTemplatesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                 esContext: EsContext,
                                                                 clusterService: RorClusterService,
                                                                 override val threadPool: ThreadPool)
  extends BaseEsRequestContext[TemplateRequestBlockContext](esContext, clusterService)
    with EsRequest[TemplateRequestBlockContext] {

  protected def templatesFrom(actionRequest: R): Set[domain.Template]

  override val initialBlockContext: TemplateRequestBlockContext = TemplateRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    templatesFrom(actionRequest)
  )
}
