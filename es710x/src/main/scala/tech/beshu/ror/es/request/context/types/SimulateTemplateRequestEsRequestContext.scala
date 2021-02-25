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

import eu.timepit.refined.auto._
import cats.data.NonEmptyList
import monix.eval.Task
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateResponse, SimulateTemplateAction}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.utils.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.domain.TemplateNamePattern
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingIndexTemplates
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

class SimulateTemplateRequestEsRequestContext(actionRequest: SimulateTemplateAction.Request,
                                              esContext: EsContext,
                                              clusterService: RorClusterService,
                                              override val threadPool: ThreadPool)
                                             (implicit generator: UniqueIdentifierGenerator)
  extends BaseTemplatesEsRequestContext[SimulateTemplateAction.Request, GettingIndexTemplates](
    actionRequest, esContext, clusterService, threadPool
  ) {

  override protected def templateOperationFrom(actionRequest: SimulateTemplateAction.Request): GettingIndexTemplates =
    GettingIndexTemplates(NonEmptyList.one(TemplateNamePattern("*")))

  override protected def modifyRequest(blockContext: BlockContext.TemplateRequestBlockContext): ModificationResult = {
    ModificationResult.UpdateResponse {
      case r: SimulateIndexTemplateResponse =>
        // todo: filter pattern and aliases
        Task.now(r)
      case other =>
        Task.now(other)
    }
  }
}
