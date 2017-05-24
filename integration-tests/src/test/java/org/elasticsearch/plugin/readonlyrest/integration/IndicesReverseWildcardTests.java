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
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.Rule;
import org.junit.Test;
import org.testcontainers.shaded.jersey.repackaged.com.google.common.collect.Lists;

import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesReverseWildcardTests extends BaseIntegrationTests {

  @Rule
  public final ESWithReadonlyRestContainer container;
  private RestClient ro;

  public IndicesReverseWildcardTests(String esProject) {
    this.container = create(
        new RorPluginGradleProject(esProject),
        "/indices_reverse_wildcards/elasticsearch.yml",
        Optional.of(client -> {
          ro = client;
          Lists.newArrayList("a1", "a2", "b1", "b2")
              .forEach(this::insertDoc);
        })
    );
  }

  // todo: for review for Simone (don't know why it does not work)
  
//  @Test
//  public void testDirectSingleIdx() throws Exception {
//    String body = search("/logstash-a1/_search");
//    assertTrue(body.contains("a1"));
//    assertFalse(body.contains("a2"));
//    assertFalse(body.contains("b1"));
//    assertFalse(body.contains("b2"));
//  }

//  @Test
//  public void testSimpleWildcard() throws Exception {
//    String body = search("/logstash-a*/_search");
//    assertTrue(body.contains("a1"));
//    assertTrue(body.contains("a2"));
//    assertFalse(body.contains("b1"));
//    assertFalse(body.contains("b2"));
//  }
//
//  @Test
//  public void testReverseWildcard() throws Exception {
//    String body = search("/logstash-*/_search");
//    assertTrue(body.contains("a1"));
//    assertTrue(body.contains("a2"));
//    assertFalse(body.contains("b1"));
//    assertFalse(body.contains("b2"));
//  }
//
//  @Test
//  public void testReverseTotalWildcard() throws Exception {
//    String body = search("/*/_search");
//
//    assertTrue(body.contains("a1"));
//    assertTrue(body.contains("a2"));
//    assertFalse(body.contains("b1"));
//    assertFalse(body.contains("b2"));
//  }
//
//  @Test
//  public void testGenericSearchAll() throws Exception {
//    String body = search("/_search");
//    assertTrue(body.contains("a1"));
//    assertTrue(body.contains("a2"));
//    assertFalse(body.contains("b1"));
//    assertFalse(body.contains("b2"));
//  }

  private void insertDoc(String docName) {
    try {
      RestClient client = container.getBasicAuthClient("admin", "container");
      HttpPut request = new HttpPut(client.from(
          "/logstash-" + docName + "/documents/doc-" + docName
      ));
      request.setHeader("refresh", "true");
      request.setHeader("timeout", "50s");
      request.setEntity(new StringEntity("{\"title\": \"" + docName + "\"}"));
      System.out.println(body(client.execute(request)));
    } catch (Exception e) {
      e.printStackTrace();
      throw new IllegalStateException("Test problem", e);
    }
  }

  private String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  private String search(String endpoint) throws Exception {
    HttpGet request = new HttpGet(ro.from(endpoint));
    request.setHeader("timeout", "50s");
    HttpResponse resp = ro.execute(request);
    assertEquals(200, resp.getStatusLine().getStatusCode());
    return body(resp);
  }
}
