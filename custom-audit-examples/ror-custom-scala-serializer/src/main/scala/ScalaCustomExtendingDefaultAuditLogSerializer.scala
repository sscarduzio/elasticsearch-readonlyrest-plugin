import org.json.JSONObject
import tech.beshu.ror.audit.AuditResponseContext
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer

class ScalaCustomExtendingDefaultAuditLogSerializer extends DefaultAuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    super.onResponse(responseContext).map { json =>
      json.put("content", responseContext.requestContext.content)
    }
  }
}
