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
package tech.beshu.ror.es.utils;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.function.Consumer;

public class ErrorContentBuilderHelper {

  public static XContentBuilder createErrorResponse(RestChannel channel, RestStatus status, Consumer<XContentBuilder> rootCause) {
    try {
      XContentBuilder builder = channel.newErrorBuilder().startObject();
      {
        builder.startObject("error");
        {
          builder.startArray("root_cause");
          {
            builder.startObject();
            rootCause.accept(builder);
            builder.endObject();
          }
          builder.endArray();
        }
        rootCause.accept(builder);
        builder.field("status", status.getStatus());
        builder.endObject();
      }
      return builder.endObject();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create forbidden response", e);
    }
  }
}
