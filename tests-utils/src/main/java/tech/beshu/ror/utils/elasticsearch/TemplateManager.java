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

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiPredicate;

import static tech.beshu.ror.utils.misc.GsonHelper.deserializeJsonBody;

public class TemplateManager {

  private final RestClient restClient;

  public TemplateManager(RestClient restClient) {
    this.restClient = restClient;
  }

  public TemplateOperationResult getTemplate(String name) {
    return makeTemplateOperation(createGetTemplateRequest(name));
  }

  public TemplateOperationResult getTemplates() {
    return makeTemplateOperation(createGetTemplatesRequest());
  }

  public void insertTemplateAndWaitForIndexing(String name, String templateContent) {
    TemplateOperationResult result = insertTemplate(name, templateContent);
    if(result.responseCode != 200) throw new IllegalStateException("Cannot insert template: [" + result.responseCode + "]\nResponse: " + result.body);
    RetryPolicy<Boolean> retryPolicy = new RetryPolicy<Boolean>()
        .handleIf(isNotIndexedYet())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(() -> isTemplateIndexed(name));
  }

  public TemplateOperationResult insertTemplate(String name, String templateContent) {
    return makeTemplateOperation(createInsertTemplateRequest(name, templateContent));
  }

  public TemplateOperationResult deleteTemplate(String name) {
    return makeTemplateOperation(createDeleteTemplateRequest(name));
  }

  private TemplateOperationResult makeTemplateOperation(HttpUriRequest request) {
    try (CloseableHttpResponse response = restClient.execute(request)) {
      return new TemplateOperationResult(response.getStatusLine().getStatusCode(), EntityUtils.toString(response.getEntity()));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpUriRequest createGetTemplateRequest(String name) {
    HttpGet request = new HttpGet(restClient.from("/_template/" + name));
    request.setHeader("timeout", "50s");
    return request;
  }

  private HttpUriRequest createGetTemplatesRequest() {
    HttpGet request = new HttpGet(restClient.from("/_template"));
    request.setHeader("timeout", "50s");
    return request;
  }

  private HttpUriRequest createDeleteTemplateRequest(String templateName) {
    HttpDelete request = new HttpDelete(restClient.from("/_template/" + templateName));
    request.setHeader("timeout", "50s");
    return request;
  }

  private HttpUriRequest createInsertTemplateRequest(String name, String templateContent) {
    try {
      HttpPut request = new HttpPut(restClient.from("/_template/" + name));
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity(templateContent));
      return request;
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot insert document", ex);
    }
  }

  private Boolean isTemplateIndexed(String templateName) {
    return getTemplates().results.containsKey(templateName);
  }

  private BiPredicate<Boolean, Throwable> isNotIndexedYet() {
    return (indexed, throwable) -> throwable != null || !indexed;
  }

  public static class TemplateOperationResult {

    private final Integer responseCode;
    private final Map<String, Object> results;
    private final String body;

    TemplateOperationResult(Integer responseCode, String body) {
      this.responseCode = responseCode;
      this.body = body;
      this.results = deserializeJsonBody(body);
    }

    public int getResponseCode() {
      return responseCode;
    }

    public Map<String, Object> getResults() {
      return results;
    }
  }
}
