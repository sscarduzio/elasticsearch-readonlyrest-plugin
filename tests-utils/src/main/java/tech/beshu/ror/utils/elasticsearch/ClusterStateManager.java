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

import com.google.common.collect.Lists;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static tech.beshu.ror.utils.misc.HttpResponseHelper.stringBodyFrom;

public class ClusterStateManager {

  private final RestClient restClient;

  public ClusterStateManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public HealthCheckResponse healthCheck() {
    return call(createHealthCheckRequest(), response -> new HealthCheckResponse(response.getStatusLine().getStatusCode()));
  }

  public CatTemplatesResponse catTemplates() {
    return call(
        createCatTemplatesRequest(Optional.empty()),
        response -> new CatTemplatesResponse(response.getStatusLine().getStatusCode(), stringBodyFrom(response))
    );
  }

  public CatTemplatesResponse catTemplates(String templateName) {
    return call(
        createCatTemplatesRequest(Optional.of(templateName)),
        response -> new CatTemplatesResponse(response.getStatusLine().getStatusCode(), stringBodyFrom(response))
    );
  }

  private <T> T call(HttpUriRequest request, Function<HttpResponse, T> resultFromResponse) {
    try (CloseableHttpResponse response = restClient.execute(request)) {
      return resultFromResponse.apply(response);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpUriRequest createHealthCheckRequest() {
    return new HttpGet(restClient.from("/_cat/health"));
  }

  private HttpUriRequest createCatTemplatesRequest(Optional<String> templateName) {
    return new HttpGet(restClient.from("/_cat/templates" + templateName.map(t -> "/" + t).orElse("")));
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

  public static class CatTemplatesResponse {

    private final Integer responseCode;
    private final List<String> results;
    private final String body;

    CatTemplatesResponse(Integer responseCode, String body) {
      this.responseCode = responseCode;
      this.body = body;
      this.results = linesFrom(body);
    }

    public int getResponseCode() {
      return responseCode;
    }

    public List<String> getResults() {
      return results;
    }

    private List<String> linesFrom(String body) {
      String[] lines = body.split(System.getProperty("line.separator"));
      if(lines.length == 0) return Lists.newArrayList();
      else if (lines.length == 1 && lines[0].isEmpty()) return Lists.newArrayList();
      else return Lists.newArrayList(lines);
    }
  }
}
