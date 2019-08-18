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

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.rest.RestRequest;
import tech.beshu.ror.adminapi.AdminRestApi;

public class RRAdminRequest extends ActionRequest {

  private RestRequest request;

  public RRAdminRequest() {
  }

  public RRAdminRequest(RestRequest request) {
    this.request = request;
  }

  public AdminRestApi.AdminRequest getAdminRequest() {
    return new AdminRestApi.AdminRequest(request.method().name(), request.path(), request.content().utf8ToString());
  }

  @Override
  public ActionRequestValidationException validate() {
    return null;
  }


}
