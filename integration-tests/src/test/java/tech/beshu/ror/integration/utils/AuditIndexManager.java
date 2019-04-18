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
package tech.beshu.ror.integration.utils;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class AuditIndexManager {

  private final RestClient restClient;
  private final String indexName;

  public AuditIndexManager(RestClient restClient, String indexName) {
    this.restClient = restClient;
    this.indexName = indexName;
  }

  public void cleanAuditIndex() throws Exception {
    HttpDelete request = new HttpDelete(restClient.from("/audit_index"));
    try (CloseableHttpResponse ignored = restClient.execute(request)) {
      // nothing to do
    }
  }

  public List<Map<String, Object>> getAuditIndexEntries() {
    RetryPolicy<List<Map<String, Object>>> retryPolicy = new RetryPolicy<List<Map<String, Object>>>()
        .handleIf(emptyEntriesResultPredicate())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10));
    return Failsafe.with(retryPolicy).get(() -> call("/" + indexName + "/_search", restClient));
  }

  private List<Map<String, Object>> call(String endpoint, RestClient client) throws Exception {
    Result response = get(endpoint, client);
    if(response.code / 100 == 2)
      return getEntries(deserializeJsonBody(response.body));
    else
      return Lists.newArrayList();
  }

  public Map<String, Object> deserializeJsonBody(String response) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>(){}.getType();
    return gson.fromJson(response, mapType);
  }

  public List<Map<String, Object>> getEntries(Map<String, Object> result) {
    List<Map<String, Object>> entries = (List<Map<String, Object>>) ((Map<String, Object>)result.get("hits")).get("hits");
    return entries.stream().map(entry -> (Map<String, Object>)entry.get("_source")).collect(Collectors.toList());
  }

  private Result get(String endpoint, RestClient client) throws Exception {
    HttpGet request = new HttpGet(client.from(endpoint));
    try (CloseableHttpResponse response = client.execute(request)) {
      return new Result(response.getStatusLine().getStatusCode(), EntityUtils.toString(response.getEntity()));
    }
  }

  private BiPredicate<List<Map<String, Object>>, Throwable> emptyEntriesResultPredicate() {
    return (maps, throwable) -> throwable != null || maps == null || maps.size() == 0;
  }

  private static class Result {
    public final Integer code;
    public final String body;

    public Result(Integer code, String body) {
      this.code = code;
      this.body = body;
    }
  }
}
