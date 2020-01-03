import java.time.{Duration, Instant}
import org.json.JSONObject
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer
import tech.beshu.ror.audit.{AuditLogSerializer, AuditResponseContext}

/*
 * Default serializer used by ROR can be extended/modified by adding new field, override existing or removing ones.
 * Note that custom serializer class extends DefaultAuditLogSerializer.
 */
class ScalaCustomAuditLogSerializer extends DefaultAuditLogSerializer {

  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
    super.onResponse(responseContext).map { json =>
      json.put("content", responseContext.requestContext.content) // adding new field
      json.remove("acl_history") // removing filed which is not needed
      json.put("processingMillis", responseContext.duration.toString) // modifying existing field
      json
    }
  }
}

/*
 * You can also create serializer from scratch extending interface AuditLogSerializer and implementing whole logic
 * of the serializer.
 */
//class ScalaCustomAuditLogSerializer extends AuditLogSerializer {
//  override def onResponse(responseContext: AuditResponseContext): Option[JSONObject] = {
//    responseContext.requestContext.loggedInUserName match {
//      case Some(user) =>
//        Some {
//          // creating new JSON object with representation of the request
//          new JSONObject()
//            .put("id", responseContext.requestContext.id)
//            .put("@timestamp", responseContext.requestContext.timestamp.toEpochMilli)
//            .put("processingMillis", responseContext.duration.toMillis)
//            .put("loggedUser", user)
//        }
//      case None =>
//        // requests which doesn't have logged user, won't be serialized
//        None
//    }
//  }
//}
