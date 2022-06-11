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
package tech.beshu.ror.es.actions.rrauthmock

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService

class TransportRRAuthMockAction(transportService: TransportService,
                                actionFilters: ActionFilters,
                                constructorDiscriminator: Unit)
  extends HandledTransportAction[RRAuthMockRequest, RRAuthMockResponse](
    RRAuthMockActionType.name, transportService, actionFilters, RRAuthMockActionType.exceptionReader
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters) =
    this(transportService, actionFilters, ())

  private val handler = new RRAuthMockActionHandler()

  override def doExecute(task: Task, request: RRAuthMockRequest, listener: ActionListener[RRAuthMockResponse]): Unit = {
    handler.handle(request, listener)
  }
}