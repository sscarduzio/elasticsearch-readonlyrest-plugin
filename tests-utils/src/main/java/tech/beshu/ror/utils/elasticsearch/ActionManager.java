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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import static tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody;

public class ActionManager {

  private final RestClient restClient;

  public ActionManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public ActionResult actionPost(String action, String payload) {
    try {
      return call(createPostActionRequest(action, payload));
    } catch (IOException e) {
      throw new IllegalStateException("Action manager '" + action + "' failed", e);
    }
  }

  public ActionResult actionPost(String action) {
    try {
      return call(createPostActionRequest(action));
    } catch (IOException e) {
      throw new IllegalStateException("Action manager '" + action + "' failed", e);
    }
  }

  public ActionResult actionGet(String action) {
    try {
      return call(createGetActionRequest(action));
    } catch (IOException e) {
      throw new IllegalStateException("Action manager '" + action + "' failed", e);
    }
  }

  private ActionResult call(HttpUriRequest request) throws IOException {
    try (CloseableHttpResponse response = restClient.execute(request)) {
      int statusCode = response.getStatusLine().getStatusCode();
      return statusCode != 200
          ? new ActionResult(statusCode, Maps.newHashMap())
          : new ActionResult(statusCode, deserializeJsonBody(EntityUtils.toString(response.getEntity())));
    }
  }

  private HttpUriRequest createPostActionRequest(String action, String payload) {
    try {
      HttpPost request = new HttpPost(restClient.from("/" + action));
      request.addHeader("Content-type", "application/json");
      request.setEntity(new StringEntity(payload));
      return request;
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpUriRequest createPostActionRequest(String action) {
    return new HttpPost(restClient.from("/" + action));
  }

  private HttpUriRequest createGetActionRequest(String action) {
    return new HttpGet(restClient.from("/" + action));
  }

  public static class ActionResult {

    private final Integer responseCode;
    private final Map<String, Object> responseJson;

    ActionResult(Integer responseCode, Map<String, Object> responseJson) {
      this.responseCode = responseCode;
      this.responseJson = responseJson;
    }

    public int getResponseCode() {
      return responseCode;
    }

    public Map<String, Object> getResponseJson() {
      return responseJson;
    }
  }
}
