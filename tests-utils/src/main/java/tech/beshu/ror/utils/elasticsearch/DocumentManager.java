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

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody;

public class DocumentManager {

  private final RestClient restClient;

  public DocumentManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public void insertDocAndWaitForRefresh(String docPath, String content) {
    makeInsertCall(docPath, content, true);
  }

  public void insertDoc(String docPath, String content) {
    makeInsertCall(docPath, content, false);
    RetryPolicy<Boolean> retryPolicy = new RetryPolicy<Boolean>()
        .handleIf(isNotIndexedYet())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(() -> isDocumentIndexed(docPath));
  }

  public void createAlias(String alias, Set<String> indexes) {
    makeCreateIndexAliasCall(alias, indexes);
    RetryPolicy<Boolean> retryPolicy = new RetryPolicy<Boolean>()
        .handleIf(isNotIndexedYet())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(() -> isAliasIndexed(alias));
  }

  private void makeInsertCall(String docPath, String content, boolean waitForRefresh) {
    try {
      HttpPut request = new HttpPut(restClient.from(
          docPath,
          waitForRefresh
              ? new ImmutableMap.Builder<String, String>().put("refresh", "wait_for").build()
              : Maps.newHashMap()
      ));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity(content));
      System.out.println(body(restClient.execute(request)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot insert document", ex);
    }
  }

  private void makeCreateIndexAliasCall(String alias, Set<String> indexes) {
    try {
      HttpPost request = new HttpPost(restClient.from("_aliases"));
      request.setHeader("Content-Type", "application/json");
      String indexesStr = Joiner.on(",").join(indexes.stream().map(s -> "\"" + s + "\"").collect(Collectors.toList()));
      request.setEntity(new StringEntity("{ \"actions\" : [ { \"add\" : { \"indices\" : [" + indexesStr + "], \"alias\" : \"" + alias +"\" } } ] }"));
      System.out.println(body(restClient.execute(request)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot insert document", ex);
    }
  }

  private Boolean isDocumentIndexed(String docPath) throws Exception {
    HttpGet request = new HttpGet(restClient.from(docPath));
    try (CloseableHttpResponse response = restClient.execute(request)) {
      Map<String, Object> jsonMap = deserializeJsonBody(body(response));
      Boolean inserted = Optional.ofNullable((Boolean) jsonMap.get("found")).orElse(false);
      System.out.println("INSERTED: " + inserted);
      return inserted;
    }
  }

  private Boolean isAliasIndexed(String aliasName) throws Exception {
    HttpGet request = new HttpGet(restClient.from(aliasName));
    try (CloseableHttpResponse response = restClient.execute(request)) {
      return response.getStatusLine().getStatusCode() == 200;
    }
  }

  private BiPredicate<Boolean, Throwable> isNotIndexedYet() {
    return (indexed, throwable) -> throwable != null || !indexed;
  }

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

}
