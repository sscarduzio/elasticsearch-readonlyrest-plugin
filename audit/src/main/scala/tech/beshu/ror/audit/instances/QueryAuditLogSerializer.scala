package tech.beshu.ror.audit.instances

import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext

class QueryAuditLogSerializer extends DefaultAuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    super.onResponse(responseContext)
      .map(_.put("content", responseContext.requestContext.content))
  }
}