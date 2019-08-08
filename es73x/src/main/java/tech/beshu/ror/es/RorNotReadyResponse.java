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

import java.io.IOException;

import static tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse;

public class RorNotReadyResponse extends BytesRestResponse {

  private RorNotReadyResponse(RestStatus status, XContentBuilder builder) {
    super(status, builder);
  }

  public static RorNotReadyResponse create(RestChannel channel) {
    return new RorNotReadyResponse(
        RestStatus.SERVICE_UNAVAILABLE,
        createErrorResponse(channel, RestStatus.SERVICE_UNAVAILABLE, RorNotReadyResponse::addRootCause)
    );
  }

  private static void addRootCause(XContentBuilder builder) {
    try {
      builder.field("reason", "Waiting for ReadonlyREST start");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create root cause", e);
    }
  }
}
