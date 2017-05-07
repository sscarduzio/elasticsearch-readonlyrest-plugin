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
package org.elasticsearch.plugin.readonlyrest.es53x;

import com.google.common.collect.Maps;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.plugin.readonlyrest.testutils.containers.ESWithReadonlyRestContainer;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import java.util.Arrays;
import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.elasticsearch.plugin.readonlyrest.testutils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesReverseWildcardTests {

  public static RestClient ro;

  @ClassRule
  public static ESWithReadonlyRestContainer container = create(
    "/indices_reverse_wildcards/elasticsearch.yml",
    Optional.of(new ESWithReadonlyRestContainer.ESInitalizer() {
      @Override
      public void initialize(RestClient client) {
        ro = client;
        Arrays.stream("a1,a2,b1,b2".split(",")).map(String::trim).forEach(docId -> {
          insertDoc(docId);
        });
      }
    })
  );

  private static void insertDoc(String docName) {
    try {
      System.out.println(body(container.getBasicAuthClient("admin", "container").performRequest(
        "PUT",
        "/logstash-" + docName + "/documents/doc-" + docName,
        Maps.newHashMap(new ImmutableMap.Builder<String, String>()
                          .put("refresh", "true")
                          .put("timeout", "50s")
                          .build()),
        new StringEntity("{\"title\": \"" + docName + "\"}")
      )));

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static String body(Response r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  private String search(String endpoint) throws Exception {
    System.out.println("> " + endpoint);
    Response resp = ro.performRequest(
      "GET",
      endpoint,
      Maps.newHashMap
        (new ImmutableMap.Builder<String, String>()
           .put("timeout", "50s")
           .build())
    );
    assertEquals(200, resp.getStatusLine().getStatusCode());
    String body = body(resp);
    System.out.println("<" + XContentHelper.convertToJson(
      StreamInput.wrap(body.getBytes()).readBytesReference(body.length()), true, true, XContentType.JSON));
    return body;
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

}
