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

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import scala.util.Either;
import scala.util.Right$;
import tech.beshu.ror.adminapi.AdminRestApi;

import java.io.IOException;

public class RRAdminResponse extends ActionResponse implements ToXContentObject {

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
      AdminRestApi.AdminResponse r = response.right().get();
      if (r.status() == 200) {
        builder.value((Object) r.body());
      } else {
        builder.startObject();
        builder.field("status", r.status());
        builder.field("message", r.body());
        builder.endObject();
      }
    } else {
      builder.startObject();
      builder.field("status", 500);
      builder.field("message", "Internal error");
      builder.endObject();
    }
    return builder;
  }
}
