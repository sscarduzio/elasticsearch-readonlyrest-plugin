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

import com.mashape.unirest.http.Unirest;
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

public class DynamicVariablesTests {

  private static RestClient adminClient;

  @ClassRule
  public static ESWithReadonlyRestContainer container =
    ESWithReadonlyRestContainer.create(
      RorPluginGradleProject.fromSystemProperty(),
      "/dynamic_vars/elasticsearch.yml",
      Optional.of(client -> {
        Unirest.setHttpClient(client.getUnderlyingClient());
        insertDoc(".kibana_simone", client);
      })
    );

  private static void insertDoc(String indexName, RestClient restClient) {
    if (adminClient == null) {
      adminClient = restClient;
    }

    String docPath = "/" + indexName + "/documents/doc-asd";
    try {
      HttpPut request = new HttpPut(restClient.from(
        docPath
      ));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setEntity(new StringEntity("{\"title\": \"" + indexName + "\"}"));
      System.out.println(body(restClient.execute(request)));

    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Test problem", e);
    }

    // Polling phase.. #TODO is there a better way?
    try {
      HttpResponse response;
      do {
        Thread.sleep(200);
        HttpHead request = new HttpHead(restClient.from(docPath));
        response = restClient.execute(request);
        System.out.println("polling for " + indexName + ".. result: " + response.getStatusLine().getReasonPhrase());
      } while (response.getStatusLine().getStatusCode() != 200);
    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Cannot configure test case", e);
    }
  }

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  @Test
  public void testUserAllowedWithUsernameAsSuffix() throws Exception {
    String body = search("/.kibana_simone/_search");
    assertTrue(body.contains("asd"));
  }


  private String search(String endpoint) throws Exception {
    String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
    RestClient userClient = container.getBasicAuthClient("simone", "dev");
    HttpGet request = new HttpGet(userClient.from(endpoint));

    request.setHeader("timeout", "50s");
    request.setHeader("x-caller-" + caller, "true");
    HttpResponse resp = userClient.execute(request);
    String body = body(resp);
    System.out.println("SEARCH RESPONSE for " + caller + ": " + body);
    assertEquals(200, resp.getStatusLine().getStatusCode());
    return body;
  }


}
