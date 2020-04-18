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

import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.UnsupportedEncodingException;
import java.util.Map;

public class ActionManagerJ extends JBaseManager {

  public ActionManagerJ(RestClient restClient) {
    super(restClient);
  }

  public JsonResponse actionPost(String action, String payload) {
    return call(createPostActionRequest(action, payload), JsonResponse::new);
  }

  public JsonResponse actionPost(String action) {
    return call(createPostActionRequest(action), JsonResponse::new);
  }

  public JsonResponse actionGet(String action, Map<String, String> queryParams) {
    return call(createGetActionRequest(action, queryParams), JsonResponse::new);
  }

  public JsonResponse actionGet(String action) {
    return call(createGetActionRequest(action), JsonResponse::new);
  }

  public JsonResponse actionDelete(String action) {
    return call(createDeleteActionRequest(action), JsonResponse::new);
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

  private HttpUriRequest createGetActionRequest(String action, Map<String,String> params) {
    return new HttpGet(restClient.from("/" + action, params));
  }

  private HttpUriRequest createDeleteActionRequest(String action) {
    return new HttpDelete(restClient.from("/" + action));
  }
}
