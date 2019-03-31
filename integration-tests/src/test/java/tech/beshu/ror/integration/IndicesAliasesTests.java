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

package tech.beshu.ror.integration;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesAliasesTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container =
      ESWithReadonlyRestContainer.create(
          RorPluginGradleProject.fromSystemProperty(), "/indices_aliases_test/elasticsearch.yml",
          Optional.of(client -> {
            insertDoc("/my_data/test/1", "{\"hello\":\"world\"}", client, true);
            insertDoc("/my_data/test/2", "{\"hello\":\"there\", \"public\":1}", client, true);
            insertDoc("/my_data/_alias/public_data", "{\"filter\":{\"term\":{\"public\":1}}}", client, false);
          })
      );

  private static void insertDoc(String docPath, String content, RestClient restClient, boolean poll) {
    try {
      HttpPut request = new HttpPut(restClient.from(docPath));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity(content));
      System.out.println(body(restClient.execute(request)));

    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Test problem", e);
    }

    // Polling phase.. #TODO is there a better way?
    try {

      if (!poll) {
        Thread.sleep(1000);
      }

      else {
        HttpResponse response;
        do {
          Thread.sleep(200);
          HttpHead request = new HttpHead(restClient.from(docPath));
          response = restClient.execute(request);
          System.out.println("polling for " + docPath + ".. result: " + response.getStatusLine().getReasonPhrase());
        } while (response.getStatusLine().getStatusCode() != 200);
      }

    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Cannot configure test case", e);
    }
  }

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  @Test
  public void testDirectIndexQuery() throws Exception {
    String body = search("/my_data/_search").body;
    assertTrue(body.contains("\"hits\":{\"total\":2"));
  }

  @Test
  public void testAliasQuery() throws Exception {
    String body = search("/public_data/_search").body;
    assertTrue(body.contains("\"hits\":{\"total\":1"));
  }

  @Test
  public void testAliasAsWildcard() throws Exception {
    String body = search("/pub*/_search").body;
    assertTrue(body.contains("\"hits\":{\"total\":1"));
  }

  // Tests with indices rule restricting to "pub*"

  @Test
  public void testRestrictedPureIndex() throws Exception {
    RestResult res = search("/my_data/_search", "restricted", "dev");
    assertEquals(401, res.status);
  }
  @Test
  public void testRestrictedAlias() throws Exception {
    String body = search("/public_data/_search", "restricted", "dev").body;
    assertTrue(body.contains("\"hits\":{\"total\":1"));
  }

  @Test
  public void testRestrictedAliasAsWildcard() throws Exception {
    String body = search("/public*/_search", "restricted", "dev").body;
    assertTrue(body.contains("\"hits\":{\"total\":1"));
  }
  @Test
  public void testRestrictedAliasAsHalfWildcard() throws Exception {
    String body = search("/pu*/_search", "restricted", "dev").body;
    assertTrue(body.contains("\"hits\":{\"total\":1"));
  }

  private RestResult search(String endpoint, String user, String pass) throws Exception {
    RestClient client = container.getBasicAuthClient(user, pass);
    return search(endpoint, client);
  }

  private RestResult search(String endpoint) throws Exception {
    return search(endpoint, container.getBasicAuthClient("unrestricted", "dev"));
  }

  private RestResult search(String endpoint, RestClient client) throws Exception {
    String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
    HttpGet request = new HttpGet(client.from(endpoint));
    request.setHeader("timeout", "50s");
    request.setHeader("x-caller-" + caller, "true");
    HttpResponse resp = client.execute(request);
    String body = body(resp);
    System.out.println("SEARCH RESPONSE for " + caller + ": " + body);
    return new RestResult(body, resp.getStatusLine().getStatusCode());
  }

  static class RestResult {
    public final String body;
    public final int status;

    public RestResult(String body, int status) {
      this.body = body;
      this.status = status;
    }
  }
}
