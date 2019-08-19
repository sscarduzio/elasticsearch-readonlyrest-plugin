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
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchManager extends BaseManager {

  private final Map<String, String> additionalHeaders;

  public SearchManager(RestClient restClient) {
    super(restClient);
    this.additionalHeaders = new HashMap<>();
  }

  public SearchManager(RestClient restClient, Map<String, String> additionalHeaders) {
    super(restClient);
    this.additionalHeaders = additionalHeaders;
  }

  @Override
  protected Map<String, String> additionalHeaders() {
    return this.additionalHeaders;
  }

  public SearchResult search(String endpoint) {
    return call(createSearchRequest(endpoint), SearchResult::new);
  }

  public MSearchResult mSearch(String query) {
    return call(createMSearchRequest(query), MSearchResult::new);
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

  public static class SearchResult extends JsonResponse {

    SearchResult(HttpResponse response) {
      super(response, true);
    }

    public List<Map<String, Object>> getSearchHits() {
      return isSuccess()
          ? (List<Map<String, Object>>) ((Map<String, Object>) getResponseJson().get("hits")).get("hits")
          : Lists.newArrayList();
    }

    public List<Map<String, Object>> getError() {
      return isSuccess()
          ? Lists.newArrayList()
          : (List<Map<String, Object>>) ((Map<String, Object>) getResponseJson().get("error")).get("root_cause");
    }
  }

  public static class MSearchResult extends JsonResponse {

    MSearchResult(HttpResponse response) {
      super(response, true);
    }

    public List<Map<String, Object>> getMSearchHits() {
      if(!isSuccess()) return Lists.newArrayList();

      List<Map<String, Object>> responses = (List<Map<String, Object>>)getResponseJson().get("responses");
      return (List<Map<String, Object>>) ((Map<String, Object>)responses.get(0).get("hits")).get("hits");
    }

    public List<Map<String, Object>> getError() {
      return isSuccess()
          ? Lists.newArrayList()
          : (List<Map<String, Object>>) ((Map<String, Object>) getResponseJson().get("error")).get("root_cause");
    }
  }

}
