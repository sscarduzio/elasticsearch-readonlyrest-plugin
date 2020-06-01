/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.rest

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, TransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService

class TransportGenericAction(transportService: TransportService,
                             actionFilters: ActionFilters,
                             ignore: Unit)
  extends TransportAction[GenericRequest, GenericResponse](GenericAction.NAME, actionFilters, transportService.getTaskManager) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters) = {
    this(transportService, actionFilters, ())
  }

  // todo:
  override def doExecute(task: Task, request: GenericRequest, listener: ActionListener[GenericResponse]): Unit = ???
}
