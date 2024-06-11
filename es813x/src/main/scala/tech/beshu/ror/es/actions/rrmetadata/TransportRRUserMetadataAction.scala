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
package tech.beshu.ror.es.actions.rrmetadata

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.TransportService

import java.util.concurrent.Executor
import scala.annotation.unused

class TransportRRUserMetadataAction(transportService: TransportService,
                                    actionFilters: ActionFilters,
                                    executor: Executor,
                                    @unused constructorDiscriminator: Unit)
  extends HandledTransportAction[RRUserMetadataRequest, RRUserMetadataResponse](
    RRUserMetadataActionType.name, transportService, actionFilters, RRUserMetadataActionType.exceptionReader[RRUserMetadataRequest], executor
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters,
           threadPool: ThreadPool) =
    this(transportService, actionFilters, threadPool.executor(ThreadPool.Names.GENERIC), ())

  override def doExecute(task: Task, request: RRUserMetadataRequest, listener: ActionListener[RRUserMetadataResponse]): Unit = {
    // nothing to do here
  }
}
