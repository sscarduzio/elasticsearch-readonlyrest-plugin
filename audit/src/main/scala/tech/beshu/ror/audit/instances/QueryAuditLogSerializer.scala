package tech.beshu.ror.audit.instances

import tech.beshu.ror.audit.AuditResponseContext

class QueryAuditLogSerializer extends DefaultAuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[Map[String, String]] = {
    super
      .onResponse(responseContext)
      .map {
        _ + ("content" -> responseContext.requestContext.content)
      }
  }
}