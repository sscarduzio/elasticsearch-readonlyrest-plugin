package tech.beshu.ror.audit

trait AuditLogSerializer {
  def onResponse(responseContext: AuditResponseContext): Option[Map[String, String]]
}
