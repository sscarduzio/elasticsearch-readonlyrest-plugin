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
