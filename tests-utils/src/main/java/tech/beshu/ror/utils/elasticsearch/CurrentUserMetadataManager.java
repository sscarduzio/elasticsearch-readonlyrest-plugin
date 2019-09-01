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
package tech.beshu.ror.utils.elasticsearch;

import org.apache.http.client.methods.HttpGet;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.Optional;

public class CurrentUserMetadataManager extends BaseManager {

  public CurrentUserMetadataManager(RestClient restClient) {
    super(restClient);
  }

  public JsonResponse fetchMetadata() {
    return call(createSearchRequest(Optional.empty()), JsonResponse::new);
  }

  public JsonResponse fetchMetadata(String preferredGroup) {
    return call(createSearchRequest(Optional.of(preferredGroup)), JsonResponse::new);
  }

  private HttpGet createSearchRequest(Optional<String> preferredGroup) {
    HttpGet request = new HttpGet(restClient.from("/_readonlyrest/metadata/current_user"));
    preferredGroup.ifPresent(group -> request.addHeader("x-ror-current-group", group));
    return request;
  }

}

