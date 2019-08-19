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
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class AuditIndexManager extends BaseManager {

  private final String indexName;

  public AuditIndexManager(RestClient restClient, String indexName) {
    super(restClient);
    this.indexName = indexName;
  }

  public SimpleResponse cleanAuditIndex() {
    return call(new HttpDelete(restClient.from("/" + indexName)), SimpleResponse::new);
  }

  public AuditIndexResponse auditIndexSearch() {
    RetryPolicy<AuditIndexResponse> retryPolicy = new RetryPolicy<AuditIndexResponse>()
        .handleIf(emptyEntriesResultPredicate())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(500))
        .withMaxDuration(Duration.ofSeconds(10));
    return Failsafe.with(retryPolicy).get(() -> call(createAuditIndexEntriesRequest(), AuditIndexResponse::new));
  }

  private HttpGet createAuditIndexEntriesRequest() {
    return new HttpGet(restClient.from("/" + indexName + "/_search"));
  }

  private BiPredicate<AuditIndexResponse, Throwable> emptyEntriesResultPredicate() {
    return (maps, throwable) -> throwable != null || maps == null || maps.getEntries().size() == 0;
  }

  public static class AuditIndexResponse extends JsonResponse {

    AuditIndexResponse(HttpResponse response) {
      super(response);
    }

    public List<Map<String, Object>> getEntries() {
      if(!isSuccess()) return Lists.newArrayList();
      List<Map<String, Object>> entries = (List<Map<String, Object>>) ((Map<String, Object>) getResponseJson().get("hits")).get("hits");
      return entries.stream().map(entry -> (Map<String, Object>)entry.get("_source")).collect(Collectors.toList());
    }
  }
}
