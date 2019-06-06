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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class ActionManager {

  private final RestClient restClient;

  public ActionManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public ActionResult action(String action, String payload) {
    try (CloseableHttpResponse response = restClient.execute(createPostActionRequest(action, payload))) {
      int statusCode = response.getStatusLine().getStatusCode();
      return new ActionResult(statusCode);
    } catch (IOException e) {
      throw new IllegalStateException("Action manager '" + action + "' failed", e);
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

  public static class ActionResult {

    private final Integer responseCode;

    ActionResult(Integer responseCode) {
      this.responseCode = responseCode;
    }

    public int getResponseCode() {
      return responseCode;
    }

  }
}
