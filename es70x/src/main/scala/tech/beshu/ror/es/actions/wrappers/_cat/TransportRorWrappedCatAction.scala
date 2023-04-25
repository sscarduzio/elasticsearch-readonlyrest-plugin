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
package tech.beshu.ror.es.actions.wrappers._cat

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService

import scala.annotation.nowarn

class TransportRorWrappedCatAction(transportService: TransportService,
                                   actionFilters: ActionFilters,
                                   @nowarn("cat=unused") constructorDiscriminator: Unit)
  extends HandledTransportAction[RorWrappedCatRequest, RorWrappedCatResponse](
    RorWrappedCatActionType.name, transportService, actionFilters, () => new RorWrappedCatRequest(() => ())
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters) =
    this(transportService, actionFilters, ())

  override def doExecute(task: Task, request: RorWrappedCatRequest,
                         listener: ActionListener[RorWrappedCatResponse]): Unit = {
    listener.onResponse(new RorWrappedCatResponse)
  }
}
