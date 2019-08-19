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
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.time.Duration;
import java.util.function.BiPredicate;

public class TemplateManager extends BaseManager {

  public TemplateManager(RestClient restClient) {
    super(restClient);
  }

  public JsonResponse getTemplate(String name) {
    return call(createGetTemplateRequest(name), JsonResponse::new);
  }

  public JsonResponse getTemplates() {
    return call(createGetTemplatesRequest(), JsonResponse::new);
  }

  public void insertTemplateAndWaitForIndexing(String name, String templateContent) {
    JsonResponse result = insertTemplate(name, templateContent);
    if(!result.isSuccess()) throw new IllegalStateException("Cannot insert template: [" + result.getResponseCode() + "]\nResponse: " + result.getRawBody());
    RetryPolicy<Boolean> retryPolicy = new RetryPolicy<Boolean>()
        .handleIf(isNotIndexedYet())
        .withMaxRetries(20)
        .withDelay(Duration.ofMillis(200));
    Failsafe.with(retryPolicy).get(() -> isTemplateIndexed(name));
  }

  public JsonResponse insertTemplate(String name, String templateContent) {
    return call(createInsertTemplateRequest(name, templateContent), JsonResponse::new);
  }

  public JsonResponse deleteTemplate(String name) {
    return call(createDeleteTemplateRequest(name), JsonResponse::new);
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
    return getTemplates().getResponseJson().containsKey(templateName);
  }

  private BiPredicate<Boolean, Throwable> isNotIndexedYet() {
    return (indexed, throwable) -> throwable != null || !indexed;
  }

}
