package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestStatus;
import tech.beshu.ror.acl.AclStaticContext;

import java.io.IOException;

public class ForbiddenResponse extends ElasticsearchStatusException {

  private final AclStaticContext aclStaticContext;

  public ForbiddenResponse(AclStaticContext aclStaticContext) {
    super(aclStaticContext.forbiddenRequestMessage(),
        aclStaticContext.doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN);
    this.aclStaticContext = aclStaticContext;
    if (aclStaticContext.doesRequirePassword()) {
      this.addHeader("WWW-Authenticate", "Basic");
    }
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
    builder.field("reason", aclStaticContext.forbiddenRequestMessage());
    return builder;
  }
}
