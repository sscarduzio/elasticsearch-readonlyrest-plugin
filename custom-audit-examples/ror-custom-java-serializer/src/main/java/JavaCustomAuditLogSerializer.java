import org.json.JSONObject;
import scala.Function1;
import scala.Option;
import tech.beshu.ror.audit.AuditLogSerializer;
import tech.beshu.ror.audit.AuditResponseContext;
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer;

/*
 * Default serializer used by ROR can be extended/modified by adding new field, override existing or removing ones.
 * Note that custom serializer class extends DefaultAuditLogSerializer.
 */
public class JavaCustomAuditLogSerializer extends DefaultAuditLogSerializer {

  @Override
  public Option<JSONObject> onResponse(AuditResponseContext responseContext) {
    return super.onResponse(responseContext).map(new Function1<JSONObject, JSONObject>() {
      @Override
      public JSONObject apply(JSONObject json) {
        json.put("content", responseContext.requestContext().content()); // adding new field
        json.remove("acl_history"); // removing filed which is not needed
        json.put("processingMillis", responseContext.duration().toString()); // modifying existing field
        return json;
      }
    });
  }
}

/*
 * You can also create serializer from scratch extending interface AuditLogSerializer and implementing whole logic
 * of the serializer.
 */
//public class JavaCustomAuditLogSerializer implements AuditLogSerializer {
//
//  public Option<JSONObject> onResponse(AuditResponseContext responseContext) {
//    Option<String> loggedUser = responseContext.requestContext().loggedInUserName();
//    if(loggedUser.isDefined()) {
//      return Option.apply(
//          // creating new JSON object with representation of the request
//          new JSONObject()
//              .put("id", responseContext.requestContext().id())
//              .put("@timestamp", responseContext.requestContext().timestamp().toEpochMilli())
//              .put("processingMillis", responseContext.duration().toMillis())
//              .put("loggedUser", loggedUser.get())
//      );
//    } else {
//      // requests which doesn't have logged user, won't be serialized
//      return Option.empty();
//    }
//  }
//}
