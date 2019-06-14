package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;

public class RorNotReadyResponse extends ElasticsearchStatusException {

  public RorNotReadyResponse() {
    super("ReadonlyREST is not ready", RestStatus.SERVICE_UNAVAILABLE);
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
    builder.field("reason", "Waiting for ReadonlyREST start");
    return builder;
  }
}
