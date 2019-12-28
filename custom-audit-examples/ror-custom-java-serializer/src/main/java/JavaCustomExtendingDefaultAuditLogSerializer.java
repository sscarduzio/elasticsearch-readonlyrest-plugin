import org.json.JSONObject;
import scala.Function1;
import scala.Option;
import tech.beshu.ror.audit.AuditResponseContext;
import tech.beshu.ror.audit.instances.DefaultAuditLogSerializer;

public class JavaCustomExtendingDefaultAuditLogSerializer extends DefaultAuditLogSerializer {

  @Override
  public Option<JSONObject> onResponse(AuditResponseContext responseContext) {
    return super.onResponse(responseContext).map(new Function1<JSONObject, JSONObject>() {
      @Override
      public JSONObject apply(JSONObject json) {
        return json.put("content", responseContext.requestContext().content());
      }
    });
  }
}
