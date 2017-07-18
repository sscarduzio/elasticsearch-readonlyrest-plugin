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
package org.elasticsearch.plugin.readonlyrest.integration;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.Lists;

import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesReverseWildcardTests {


  @ClassRule
  public static ESWithReadonlyRestContainer container =
    create(
      RorPluginGradleProject.fromSystemProperty(),
      "/indices_reverse_wildcards/elasticsearch.yml",
      Optional.of(client -> {
        Lists.newArrayList("a1", "a2", "b1", "b2").forEach(doc -> insertDoc(doc, client));
        try {
          HttpResponse response;
          do {
            HttpHead request = new HttpHead(client.from("/logstash-b2/documents/doc-b2" ));
            response = client.execute(request);
            EntityUtils.consume(response.getEntity());
          } while (response.getStatusLine().getStatusCode() != 200);
        } catch (Exception e) {
          e.printStackTrace();
          throw new IllegalStateException("Cannot configure test case", e);
        }
      })
    );

  private static RestClient adminClient;

  private static void insertDoc(String docName, RestClient restClient) {
    if(adminClient == null) {
      adminClient = restClient;
    }

    try {
      HttpPut request = new HttpPut(restClient.from(
        "/logstash-" + docName + "/documents/doc-" + docName
      ));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setEntity(new StringEntity("{\"title\": \"" + docName + "\"}"));
      System.out.println(body(restClient.execute(request)));

    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Test problem", e);
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
