/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
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
import tech.beshu.ror.requestcontext.RequestContext;

import java.io.IOException;
import java.util.Map;

public class RRMetadataResponse extends ActionResponse implements ToXContent {

  private RequestContext requestContext;
  private Throwable throwable;

  public RRMetadataResponse(RequestContext requestContext) {
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
