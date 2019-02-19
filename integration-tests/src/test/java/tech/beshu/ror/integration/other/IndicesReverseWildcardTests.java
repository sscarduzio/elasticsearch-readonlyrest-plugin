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
package tech.beshu.ror.integration.other;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.Lists;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesReverseWildcardTests {

  private static RestClient adminClient;

  @ClassRule
  public static ESWithReadonlyRestContainer container =
    ESWithReadonlyRestContainer.create(
      RorPluginGradleProject.fromSystemProperty(), "/indices_reverse_wildcards/elasticsearch.yml",
      Optional.of(client -> {
        Lists.newArrayList("a1", "a2", "b1", "b2").forEach(doc -> insertDoc(doc, client));
      })
    );

  private static void insertDoc(String docName, RestClient restClient) {
    if (adminClient == null) {
      adminClient = restClient;
    }

    try {
      HttpPut request = new HttpPut(restClient.from(
        "/logstash-" + docName + "/documents/doc-" + docName
      ));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setHeader("Content-Type", "application/json");
      request.setEntity(new StringEntity("{\"title\": \"" + docName + "\"}"));
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
        HttpHead request = new HttpHead(restClient.from("/logstash-" + docName + "/documents/doc-" + docName));
        response = restClient.execute(request);
        System.out.println("polling for " + docName + ".. result: " + response.getStatusLine().getReasonPhrase());
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
  public void testDirectSingleIdx() throws Exception {
    String body = search("/logstash-a1/_search");
    assertTrue(body.contains("a1"));
    assertFalse(body.contains("a2"));
    assertFalse(body.contains("b1"));
    assertFalse(body.contains("b2"));
  }

  @Test
  public void testSimpleWildcard() throws Exception {
    String body = search("/logstash-a*/_search");
    assertTrue(body.contains("a1"));
    assertTrue(body.contains("a2"));
    assertFalse(body.contains("b1"));
    assertFalse(body.contains("b2"));
  }

  @Test
  public void testReverseWildcard() throws Exception {
    String body = search("/logstash-*/_search");
    assertTrue(body.contains("a1"));
    assertTrue(body.contains("a2"));
    assertFalse(body.contains("b1"));
    assertFalse(body.contains("b2"));
  }

  @Test
  public void testReverseTotalWildcard() throws Exception {
    String body = search("/*/_search");

    assertTrue(body.contains("a1"));
    assertTrue(body.contains("a2"));
    assertFalse(body.contains("b1"));
    assertFalse(body.contains("b2"));
  }

  @Test
  public void testGenericSearchAll() throws Exception {
    String body = search("/_search");
    assertTrue(body.contains("a1"));
    assertTrue(body.contains("a2"));
    assertFalse(body.contains("b1"));
    assertFalse(body.contains("b2"));
  }

  private String search(String endpoint) throws Exception {
    String caller = Thread.currentThread().getStackTrace()[2].getMethodName();
    HttpGet request = new HttpGet(adminClient.from(endpoint));
    request.setHeader("timeout", "50s");
    request.setHeader("x-caller-" + caller, "true");
    HttpResponse resp = adminClient.execute(request);
    String body = body(resp);
    System.out.println("SEARCH RESPONSE for " + caller + ": " + body);
    assertEquals(200, resp.getStatusLine().getStatusCode());
    return body;
  }
}
