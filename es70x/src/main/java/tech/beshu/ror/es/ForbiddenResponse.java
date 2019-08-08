/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;
import tech.beshu.ror.acl.AclActionHandler;
import tech.beshu.ror.acl.AclStaticContext;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse;

public class ForbiddenResponse extends BytesRestResponse {

  private ForbiddenResponse(RestStatus status, XContentBuilder builder) {
    super(status, builder);
  }

  public static ForbiddenResponse create(RestChannel channel,
      List<AclActionHandler.ForbiddenCause> causes, AclStaticContext aclStaticContext) {
    RestStatus restStatus = responseRestStatus(aclStaticContext);
    ForbiddenResponse response = new ForbiddenResponse(
        restStatus,
        createErrorResponse(channel, restStatus, builder -> addRootCause(builder, causes, aclStaticContext))
    );
    if (aclStaticContext.doesRequirePassword()) {
      response.addHeader("WWW-Authenticate", "Basic");
    }
    return response;
  }

  private static void addRootCause(XContentBuilder builder, List<AclActionHandler.ForbiddenCause> causes,
      AclStaticContext aclStaticContext) {
    try {
      builder.field("reason", aclStaticContext.forbiddenRequestMessage());
      builder.field("due_to", causes.stream().map(AclActionHandler.ForbiddenCause::stringify).collect(Collectors.toList()));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create root cause", e);
    }
  }

  private static RestStatus responseRestStatus(AclStaticContext aclStaticContext) {
    return aclStaticContext.doesRequirePassword() ? RestStatus.UNAUTHORIZED : RestStatus.FORBIDDEN;
  }
}
