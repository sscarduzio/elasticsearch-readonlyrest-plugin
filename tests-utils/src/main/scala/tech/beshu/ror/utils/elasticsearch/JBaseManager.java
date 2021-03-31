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

import com.google.common.collect.Maps;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import tech.beshu.ror.utils.httpclient.RestClient;
import tech.beshu.ror.utils.httpclient.HttpResponseHelper;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import static tech.beshu.ror.utils.httpclient.HttpResponseHelper.stringBodyFrom;

public abstract class JBaseManager {

  protected final RestClient restClient;

  protected JBaseManager(RestClient restClient) {
    this.restClient = restClient;
  }

  protected <T extends SimpleResponse> T call(HttpUriRequest request, Function<HttpResponse, T> resultFromResponse) {
    additionalHeaders().forEach(request::addHeader);
    try (CloseableHttpResponse response = restClient.execute(request)) {
      return resultFromResponse.apply(response);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected Map<String, String> additionalHeaders() {
    return Maps.newHashMap();
  }

  public static class SimpleResponse {

    private final Integer responseCode;
    private final String body;

    SimpleResponse(HttpResponse response) {
      this.responseCode = response.getStatusLine().getStatusCode();
      this.body = stringBodyFrom(response);
    }

    public int getResponseCode() {
      return responseCode;
    }

    public boolean isSuccess() {
      return getResponseCode() / 100 == 2;
    }

    public String getBody() {
      return body;
    }
  }

  public static class JsonResponse extends SimpleResponse {

    private final Map<String, Object> responseJson;

    JsonResponse(HttpResponse response) {
      this(response, false);
    }

    JsonResponse(HttpResponse response, Boolean withJsonError) {
      super(response);
      this.responseJson = isSuccess() || withJsonError
          ? HttpResponseHelper.deserializeJsonBody(getBody())
          : Maps.newHashMap();
    }

    public Map<String, Object> getResponseJsonMap() {
      return responseJson;
    }

    public String getRawBody() { return getBody(); }
  }
}
