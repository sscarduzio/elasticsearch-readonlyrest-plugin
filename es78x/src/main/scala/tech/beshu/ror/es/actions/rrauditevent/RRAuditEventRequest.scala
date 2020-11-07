package tech.beshu.ror.es.actions.rrauditevent

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.json.JSONObject

class RRAuditEventRequest(val auditEvents: JSONObject) extends ActionRequest {
  override def validate(): ActionRequestValidationException = null
}
