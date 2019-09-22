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
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.Optional;

public class ClusterStateManagerJ extends JBaseManager {

  public ClusterStateManagerJ(RestClient restClient) {
    super(restClient);
  }

  public SimpleResponse healthCheck() {
    return call(createHealthCheckRequest(), SimpleResponse::new);
  }

  public TextLinesResponse catTemplates() {
    return call(createCatTemplatesRequest(Optional.empty()), TextLinesResponse::new);
  }

  public TextLinesResponse catTemplates(String templateName) {
    return call(createCatTemplatesRequest(Optional.of(templateName)), TextLinesResponse::new);
  }

  private HttpUriRequest createHealthCheckRequest() {
    return new HttpGet(restClient.from("/_cat/health"));
  }

  private HttpUriRequest createCatTemplatesRequest(Optional<String> templateName) {
    return new HttpGet(restClient.from("/_cat/templates" + templateName.map(t -> "/" + t).orElse("")));
  }

}
