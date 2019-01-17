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

package tech.beshu.ror.es.rradmin;

import com.google.common.collect.Maps;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.requestcontext.__old_RequestContext;

import java.io.IOException;
import java.util.Map;

public class RRMetadataResponse extends ActionResponse implements ToXContent {

  private __old_RequestContext requestContext;
  private Throwable throwable;

  public RRMetadataResponse(__old_RequestContext requestContext) {
    this.requestContext = requestContext;
  }

  public RRMetadataResponse(Throwable t) {
    this.throwable = t;
  }

  @Override
  public void writeTo(StreamOutput out) throws IOException {
    super.writeTo(out);
  }

  @Override
  public void readFrom(StreamInput in) throws IOException {
    super.readFrom(in);
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
    Map<String, Object> sourceMap = Maps.newHashMap();

    sourceMap.putAll(requestContext.getResponseHeaders());

    requestContext.getLoggedInUser().ifPresent(u -> {
      sourceMap.put(Constants.HEADER_USER_ROR, u.getId());
      sourceMap.put(Constants.HEADER_GROUP_CURRENT, u.getCurrentGroup().orElse(null));
      sourceMap.put(Constants.HEADER_GROUPS_AVAILABLE, u.getAvailableGroups());
    });

    String hiddenAppsStr = requestContext.getResponseHeaders().get(Constants.HEADER_KIBANA_HIDDEN_APPS);
    String[] hiddenApps = Strings.isNullOrEmpty(hiddenAppsStr) ? new String[] {} : hiddenAppsStr.split(",");
    sourceMap.put(Constants.HEADER_KIBANA_HIDDEN_APPS, hiddenApps);

    for (Map.Entry<String, Object> kv : sourceMap.entrySet()) {
      builder.field(kv.getKey(), kv.getValue());
    }
    return builder;
  }
}
