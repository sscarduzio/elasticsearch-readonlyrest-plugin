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
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchManager {

  private final RestClient restClient;

  public SearchManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public SearchResult search(String endpoint) {
    try (CloseableHttpResponse response = restClient.execute(createSearchRequest(endpoint))) {
      int statusCode = response.getStatusLine().getStatusCode();
      return statusCode != 200
          ? new SearchResult(statusCode, Lists.newArrayList())
          : new SearchResult(statusCode, getSearchHits(deserializeJsonBody(bodyFrom(response))));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public SearchResult mSearch(String query) {
    try {
      try (CloseableHttpResponse response = restClient.execute(createMSearchRequest(query))) {
        int statusCode = response.getStatusLine().getStatusCode();
        return statusCode != 200
            ? new SearchResult(statusCode, Lists.newArrayList())
            : new SearchResult(statusCode, getMSearchHits(deserializeJsonBody(EntityUtils.toString(response.getEntity()))));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpGet createSearchRequest(String endpoint) {
    String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
    HttpGet request = new HttpGet(restClient.from(endpoint));
    request.setHeader("timeout", "50s");
    request.setHeader("x-caller-" + caller, "true");
    return request;
  }

  private HttpPost createMSearchRequest(String query) {
    try {
      HttpPost request = new HttpPost(restClient.from("/_msearch"));
      request.addHeader("Content-type", "application/json");
      request.setEntity(new StringEntity(query));
      return request;
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String bodyFrom(HttpResponse r) {
    try {
      return EntityUtils.toString(r.getEntity());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot get string body", e);
    }
  }

  private static Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

  private static List<Map<String, Object>> getSearchHits(Map<String, Object> result) {
    return (List<Map<String, Object>>) ((Map<String, Object>)result.get("hits")).get("hits");
  }

  private static List<Map<String, Object>> getMSearchHits(Map<String, Object> result) {
    List<Map<String, Object>> responses = (List<Map<String, Object>>)result.get("responses");
    return (List<Map<String, Object>>) ((Map<String, Object>)responses.get(0).get("hits")).get("hits");
  }

  public static class SearchResult {

    private final Integer responseCode;
    private final List<Map<String, Object>> results;

    SearchResult(Integer responseCode, List<Map<String, Object>> results) {
      this.responseCode = responseCode;
      this.results = results;
    }

    public int getResponseCode() {
      return responseCode;
    }

    public List<Map<String, Object>> getResults() {
      return results;
    }
  }

}
