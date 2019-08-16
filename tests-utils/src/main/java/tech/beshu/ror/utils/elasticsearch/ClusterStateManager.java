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

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;

public class ClusterStateManager {

  private final RestClient restClient;

  public ClusterStateManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public HealthCheckResponse healthCheck() {
    try (CloseableHttpResponse response = restClient.execute(createHealthCheckRequest())) {
      return new HealthCheckResponse(response.getStatusLine().getStatusCode());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpUriRequest createHealthCheckRequest() {
    return new HttpGet(restClient.from("/_cat/health"));
  }

  public static class HealthCheckResponse {

    private final Integer responseCode;

    HealthCheckResponse(Integer responseCode) {
      this.responseCode = responseCode;
    }

    public int getResponseCode() {
      return responseCode;
    }
  }
}
