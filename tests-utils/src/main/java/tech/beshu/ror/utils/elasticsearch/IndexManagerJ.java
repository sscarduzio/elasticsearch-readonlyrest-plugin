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

import com.google.common.collect.Sets;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class IndexManagerJ extends JBaseManager {

  public IndexManagerJ(RestClient restClient) {
    super(restClient);
  }

  public GetIndexResult get(String indexName) {
    return call(createGetIndexRequest(indexName), GetIndexResult::new);
  }

  public SimpleResponse removeAll() {
    return call(createDeleteIndicesRequest(), SimpleResponse::new);
  }

  public SimpleResponse remove(String indexName) {
    return call(createDeleteIndex(indexName), GetIndexResult::new);
  }

  public SimpleResponse close(String indexName) {
    return call(createCloseIndex(indexName), SimpleResponse::new);
  }

  private HttpUriRequest createGetIndexRequest(String indexName) {
    return new HttpGet(restClient.from("/" + indexName));
  }

  private HttpUriRequest createDeleteIndex(String indexName) {
    return new HttpDelete(restClient.from("/" + indexName));
  }

  private HttpUriRequest createCloseIndex(String indexName) {
    return new HttpPost(restClient.from("/" + indexName + "/_close"));
  }

  private HttpUriRequest createDeleteIndicesRequest() {
    return new HttpDelete(restClient.from("/_all"));
  }

  public static class GetIndexResult extends JsonResponse {

    GetIndexResult(HttpResponse response) {
      super(response);
    }

    public Set<String> getAliases() {
      if(!isSuccess()) return Sets.newHashSet();
      List<Object> responses = getResponseJsonMap().values().stream().collect(Collectors.toList());
      return ((Map<String, Object>) ((Map<String, Object>)responses.get(0)).get("aliases")).keySet();
    }
  }
}
