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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

public class DocumentManager {

  private final RestClient restClient;

  public DocumentManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public void insertDoc(String docPath, String content) {
    makeInsertCall(docPath, content);
    RetryPolicy<Boolean> retryPolicy = new RetryPolicy<Boolean>()
        .handleIf(documentIsNotIndexedYet())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(() -> isDocumentIndexed(docPath));
  }

  private void makeInsertCall(String docPath, String content) {
    try {
      HttpPut request = new HttpPut(restClient.from(docPath));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity(content));
      System.out.println(body(restClient.execute(request)));
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot insert document", ex);
    }
  }

  private Boolean isDocumentIndexed(String docPath) throws Exception {
    HttpGet request = new HttpGet(restClient.from(docPath));
    try (CloseableHttpResponse response = restClient.execute(request)) {
      Map<String, Object> jsonMap = deserializeJsonBody(body(response));
      Boolean inserted = (Boolean) jsonMap.get("found");
      System.out.println("INSERTED: " + inserted);
      return inserted;
    }
  }

  private BiPredicate<Boolean, Throwable> documentIsNotIndexedYet() {
    return (indexed, throwable) -> throwable != null || !indexed;
  }

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  private Map<String, Object> deserializeJsonBody(String body) {
    Gson gson = new Gson();
    Type mapType = new TypeToken<HashMap<String, Object>>() {
    }.getType();
    return gson.fromJson(body, mapType);
  }
}
