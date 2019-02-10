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
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import scala.collection.JavaConverters$;
import tech.beshu.ror.Constants;
import tech.beshu.ror.acl.aDomain;
import tech.beshu.ror.acl.blocks.BlockContext;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class RRMetadataResponse extends ActionResponse implements ToXContentObject {

  private BlockContext blockContext;

  public RRMetadataResponse(BlockContext blockContext) {
    this.blockContext = blockContext;
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

    Set<aDomain.Header> headers = JavaConverters$.MODULE$.<aDomain.Header>setAsJavaSet(blockContext.responseHeaders());
    headers.forEach(h -> sourceMap.put(h.name().value().toString(), h.value().toString()));

    blockContext.loggedUser().foreach(u -> {
      sourceMap.put(Constants.HEADER_USER_ROR, u.id().value());
      return null;
    });

    blockContext.currentGroup().foreach(g -> {
      sourceMap.put(Constants.HEADER_GROUP_CURRENT, g.value().toString());
      return null;
    });

    if(!blockContext.availableGroups().isEmpty()) {
      String availableGroupsString = JavaConverters$.MODULE$.<aDomain.Group>setAsJavaSet(blockContext.availableGroups())
          .stream()
          .map(g -> g.value().toString())
          .collect(Collectors.joining(","));
      sourceMap.put(Constants.HEADER_GROUPS_AVAILABLE, availableGroupsString);
    }

    String hiddenAppsStr = headers
        .stream()
        .filter(h -> Constants.HEADER_KIBANA_HIDDEN_APPS.equals(h.name().value().toString()))
        .findFirst()
        .map(h -> h.value().toString())
        .orElse(null);

    String[] hiddenApps = Strings.isNullOrEmpty(hiddenAppsStr) ? new String[] {} : hiddenAppsStr.split(",");
    sourceMap.put(Constants.HEADER_KIBANA_HIDDEN_APPS, hiddenApps);

    builder.map(sourceMap);
    return builder;
  }
}
