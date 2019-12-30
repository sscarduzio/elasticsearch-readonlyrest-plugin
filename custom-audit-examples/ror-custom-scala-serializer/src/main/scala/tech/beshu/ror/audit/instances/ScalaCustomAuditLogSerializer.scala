package tech.beshu.ror.audit.instances

import org.json.JSONObject
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

class ScalaCustomAuditLogSerializer extends AuditLogSerializer {
  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = Some {
    new JSONObject()
      .put("id", responseContext.requestContext.id)
      .put("@timestamp", responseContext.requestContext.timestamp.toEpochMilli)
      .put("processingMillis", responseContext.duration.toMillis)
  }
}
