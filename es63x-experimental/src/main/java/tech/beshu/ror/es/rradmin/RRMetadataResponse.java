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
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import scala.collection.JavaConverters$;
import tech.beshu.ror.acl.blocks.BlockContext;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.aDomain;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public class RRMetadataResponse extends ActionResponse implements ToXContentObject {

  private BlockContext blockContext;
  private Throwable throwable;

  public RRMetadataResponse(BlockContext blockContext) {
    this.blockContext = blockContext;
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
  public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
    Map<String, Object> sourceMap = Maps.newHashMap();

    Set<aDomain.Header> headers = JavaConverters$.MODULE$.setAsJavaSet(blockContext.responseHeaders());
    headers.forEach(h -> sourceMap.put(h.name().copy$default$1(), h.value()));

    blockContext.loggedUser().foreach(u -> {
      sourceMap.put(Constants.HEADER_USER_ROR, u.id().value());
      // todo: fixme where user will have groups implemented
      //sourceMap.put(Constants.HEADER_GROUP_CURRENT, u.getCurrentGroup().orElse(null));
      //sourceMap.put(Constants.HEADER_GROUPS_AVAILABLE, u.getAvailableGroups());
      return null;
    });

    String hiddenAppsStr = headers
        .stream()
        .filter(h -> Constants.HEADER_KIBANA_HIDDEN_APPS.equals(h.name().value()))
        .findFirst()
        .map(aDomain.Header::value)
        .orElse(null);

    String[] hiddenApps = Strings.isNullOrEmpty(hiddenAppsStr) ? new String[] {} : hiddenAppsStr.split(",");
    sourceMap.put(Constants.HEADER_KIBANA_HIDDEN_APPS, hiddenApps);

    builder.map(sourceMap);
    return builder;
  }
}
