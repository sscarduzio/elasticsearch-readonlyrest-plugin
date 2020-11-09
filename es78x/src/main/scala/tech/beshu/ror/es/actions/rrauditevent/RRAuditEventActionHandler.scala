package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.ActionListener

object RRAuditEventActionHandler {

  def handle(request: RRAuditEventRequest, listener: ActionListener[RRAuditEventResponse]): Unit = {
    listener.onResponse(new RRAuditEventResponse())
  }
}
