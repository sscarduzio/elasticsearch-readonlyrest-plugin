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
import tech.beshu.ror.accesscontrol.domain.TemplateLike
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

class PutComposableIndexTemplateEsRequestContext(actionRequest: ActionRequest,
                                                 esContext: EsContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseSingleTemplateEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

//  override protected def templateFrom(request: ActionRequest): domain.TemplateLike =
//    domain.TemplateLike(TemplateName("test"), UniqueNonEmptyList.of(IndexName.fromUnsafeString("index1")))
//
//  override protected def update(request: ActionRequest, template: domain.TemplateLike): ModificationResult = {
//    val t = this.allTemplates
//    ModificationResult.Modified
//  }
  override protected def templateFrom(request: ActionRequest): TemplateLike.IndexTemplate = ???

  override protected def update(request: ActionRequest, template: TemplateLike.IndexTemplate): ModificationResult = ???
}

object PutComposableIndexTemplateEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[PutComposableIndexTemplateEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getName.endsWith("PutComposableIndexTemplateAction$Request")) {
      Some(new PutComposableIndexTemplateEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}
