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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody;

public class IndexManager {

  private final RestClient restClient;

  public IndexManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public GetIndexResult get(String indexName) {
    try (CloseableHttpResponse response = restClient.execute(createGetIndexRequest(indexName))) {
      int statusCode = response.getStatusLine().getStatusCode();
      return statusCode != 200
          ? new GetIndexResult(statusCode, Sets.newHashSet())
          : new GetIndexResult(statusCode, getAliases(deserializeJsonBody(bodyFrom(response))));
    } catch (IOException e) {
      throw new IllegalStateException("Index manager get index result failed", e);
    }
  }


  private HttpUriRequest createGetIndexRequest(String indexName) {
    return new HttpGet(restClient.from("/" + indexName));
  }

  private static Set<String> getAliases(Map<String, Object> result) {
    List<Object> responses = result.values().stream().collect(Collectors.toList());
    return ((Map<String, Object>) ((Map<String, Object>)responses.get(0)).get("aliases")).keySet();
  }

  private static String bodyFrom(HttpResponse r) {
    try {
      return EntityUtils.toString(r.getEntity());
    } catch (IOException e) {
      throw new IllegalStateException("Cannot get string body", e);
    }
  }

  public static class GetIndexResult {

    private final Integer responseCode;
    private final ImmutableSet<String> aliases;

    GetIndexResult(Integer responseCode, Set<String> aliases) {
      this.responseCode = responseCode;
      this.aliases = ImmutableSet.copyOf(aliases);
    }

    public int getResponseCode() {
      return responseCode;
    }

    public ImmutableSet<String> getAliases() {
      return aliases;
    }
  }
}
