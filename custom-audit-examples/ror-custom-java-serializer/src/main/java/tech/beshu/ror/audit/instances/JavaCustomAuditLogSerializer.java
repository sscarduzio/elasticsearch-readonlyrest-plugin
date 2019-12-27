package tech.beshu.ror.audit.instances;

import org.json.JSONObject;
import scala.Option;
import tech.beshu.ror.audit.AuditLogSerializer;
import tech.beshu.ror.audit.AuditResponseContext;

public class JavaCustomAuditLogSerializer implements AuditLogSerializer {

  public Option<JSONObject> onResponse(AuditResponseContext responseContext) {
    return Option.apply(
        new JSONObject()
            .put("id", responseContext.requestContext().id())
            .put("@timestamp", responseContext.requestContext().timestamp().toEpochMilli())
            .put("processingMillis", responseContext.duration().toMillis())
    );
  }
}
