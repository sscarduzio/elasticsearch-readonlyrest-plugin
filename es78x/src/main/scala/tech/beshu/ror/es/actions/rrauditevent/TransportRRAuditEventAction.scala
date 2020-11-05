package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService

class TransportRRAuditEventAction(transportService: TransportService,
                                  actionFilters: ActionFilters,
                                  constructorDiscriminator: Unit)
  extends HandledTransportAction[RRAuditEventRequest, RRAuditEventResponse](
    RRAuditEventActionType.name, transportService, actionFilters, RRAuditEventActionType.exceptionReader
  ) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters) =
    this(transportService, actionFilters, ())

  override def doExecute(task: Task, request: RRAuditEventRequest,
                         listener: ActionListener[RRAuditEventResponse]): Unit = {
    listener.onResponse(new RRAuditEventResponse())
  }
}
