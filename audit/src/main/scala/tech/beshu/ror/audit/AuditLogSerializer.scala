package tech.beshu.ror.audit

import org.json.JSONObject

trait AuditLogSerializer {
  def onResponse(responseContext: AuditResponseContext): Option[JSONObject]
}
