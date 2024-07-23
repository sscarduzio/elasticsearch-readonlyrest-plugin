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
package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.TransportService

import scala.annotation.unused

class TransportRRAuditEventAction(settings: Settings,
                                  threadPool: ThreadPool,
                                  transportService: TransportService,
                                  actionFilters: ActionFilters,
                                  indexNameExpressionResolver: IndexNameExpressionResolver,
                                  @unused constructorDiscriminator: Unit)
  extends HandledTransportAction[RRAuditEventRequest, RRAuditEventResponse](
    settings, RRAuditEventActionType.name, threadPool, transportService, actionFilters, indexNameExpressionResolver, () => new RRAuditEventRequest
  ) {

  @Inject
  def this(settings: Settings,
           threadPool: ThreadPool,
           transportService: TransportService,
           actionFilters: ActionFilters,
           indexNameExpressionResolver: IndexNameExpressionResolver) =
    this(settings, threadPool, transportService, actionFilters, indexNameExpressionResolver, ())

  override def doExecute(request: RRAuditEventRequest, listener: ActionListener[RRAuditEventResponse]): Unit = {
    RRAuditEventActionHandler.handle(request, listener)
  }
}
