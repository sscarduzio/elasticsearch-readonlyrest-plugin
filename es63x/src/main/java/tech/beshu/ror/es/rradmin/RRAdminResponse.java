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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import scala.util.Either;
import scala.util.Right$;
import tech.beshu.ror.adminapi.AdminRestApi;

import java.io.IOException;

public class RRAdminResponse extends ActionResponse implements ToXContentObject {

  private static final Logger logger = LogManager.getLogger(RRAdminResponse.class);
  private final Either<Throwable, AdminRestApi.AdminResponse> response;

  public RRAdminResponse(Either<Throwable, AdminRestApi.AdminResponse> response) {
    this.response = response;
  }

  public RRAdminResponse(AdminRestApi.AdminResponse response) {
    this.response = Right$.MODULE$.apply(response);
  }

  @Override
  public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
    if(response.isRight()) {
      AdminRestApi.ApiCallResult result = response.right().get().result();
      if(result instanceof AdminRestApi.Success) {
        AdminRestApi.Success success = (AdminRestApi.Success) result;
        addResponseJson(builder,"ok", success.message());
      } else if (result instanceof AdminRestApi.Failure) {
        AdminRestApi.Failure failure = (AdminRestApi.Failure) result;
        addResponseJson(builder,"ko", failure.message());
      } else {
        logger.error("RRAdmin: unknown type of response");
        addResponseJson(builder,"ko", AdminRestApi.AdminResponse$.MODULE$.internalError().result().message());
      }
    } else {
      logger.error("RRAdmin internal error", response.left().get());
      addResponseJson(builder, "ko", AdminRestApi.AdminResponse$.MODULE$.internalError().result().message());
    }
    return builder;
  }

  private void addResponseJson(XContentBuilder builder, String status, String message) throws IOException {
    builder.startObject();
    builder.field("status", status);
    builder.field("message", message);
    builder.endObject();
  }
}
