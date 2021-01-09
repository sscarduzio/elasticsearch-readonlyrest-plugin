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
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class DocumentManagerJ extends JBaseManager {

  public DocumentManagerJ(RestClient restClient) {
    super(restClient);
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

  public void removeDoc(String docPath) {
    call(createRemoveDocRequest(docPath, true), SimpleResponse::new);
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
    SimpleResponse response = call(createInsertDocRequest(docPath, content, waitForRefresh), SimpleResponse::new);
    if(!response.isSuccess()) {
      throw new IllegalStateException("Cannot insert document. Response code: " + response.getResponseCode() + ", body: " + response.getBody());
    }
  }

  private HttpPut createInsertDocRequest(String docPath, String content, boolean waitForRefresh) {
    try {
      HttpPut request = new HttpPut(restClient.from(
          docPath,
          waitForRefresh
              ? new ImmutableMap.Builder<String, String>().put("refresh", "wait_for").build()
              : Maps.newHashMap()
      ));
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity(content));
      return request;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private HttpDelete createRemoveDocRequest(String docPath, boolean waitForRefresh) {
    try {
      HttpDelete request = new HttpDelete(restClient.from(
          docPath,
          waitForRefresh
              ? new ImmutableMap.Builder<String, String>().put("refresh", "wait_for").build()
              : Maps.newHashMap()
      ));
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      return request;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void makeCreateIndexAliasCall(String alias, Set<String> indexes) {
    SimpleResponse response = call(createInsertIndexAliasRequest(alias, indexes), SimpleResponse::new);
    if(!response.isSuccess()) throw new IllegalStateException("Cannot insert document");
  }

  private HttpPost createInsertIndexAliasRequest(String alias, Set<String> indexes) {
    try {
      HttpPost request = new HttpPost(restClient.from("_aliases"));
      request.setHeader("Content-Type", "application/json");
      String indexesStr = Joiner.on(",").join(indexes.stream().map(s -> "\"" + s + "\"").collect(Collectors.toList()));
      request.setEntity(new StringEntity("{ \"actions\" : [ { \"add\" : { \"indices\" : [" + indexesStr + "], \"alias\" : \"" + alias +"\" } } ] }"));
      return request;
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private Boolean isDocumentIndexed(String docPath) {
    JsonResponse response = call(new HttpGet(restClient.from(docPath)), JsonResponse::new);
    return Optional.ofNullable((Boolean) response.getResponseJsonMap().get("found")).orElse(false);
  }

  private Boolean isAliasIndexed(String aliasName) {
    return call(new HttpGet(restClient.from(aliasName)), SimpleResponse::new).isSuccess();
  }

  private BiPredicate<Boolean, Throwable> isNotIndexedYet() {
    return (indexed, throwable) -> throwable != null || !indexed;
  }

}
