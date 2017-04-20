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

import com.google.common.collect.Maps;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesRewriteTests {

  public static RestClient ro;
  public static RestClient kibana;

  @ClassRule
  public static ESWithReadonlyRestContainer container = create(
    "/indices_rewrite/elasticsearch.yml",
    Optional.of(new ESWithReadonlyRestContainer.ESInitalizer() {
      @Override
      public void initialize(RestClient client) {
        ro = container.getBasicAuthClient("simone", "ro_pass");
        kibana = container.getBasicAuthClient("kibana", "kibana");

        try {
          System.out.println(body(kibana.performRequest(
            "PUT",
            "/.kibana_simone/doc/1",
            Maps.newHashMap(new ImmutableMap.Builder<String, String>()
                              .put("refresh", "true")
                              .put("timeout", "50s")
                              .build()),
            new StringEntity("{\"helloWorld\": true}")
          )));

        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    })
  );

  private static String body(Response r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  @Test
  public void testCreateIndex() throws Exception {


    String indicesList = body(kibana.performRequest("GET", "/_cat/indices"));
    assertTrue(indicesList.contains(".kibana_simone"));
  }

  @Test
  public void testSearch() throws Exception {
    generalCheck(ro.performRequest("GET", "/.kibana/_search"));
  }

  @Test
  public void testMultiSearch() throws Exception {
    generalCheck(ro.performRequest(
      "GET",
      "/_msearch",
      Maps.newHashMap(),
      new StringEntity("{\"index\" : \".kibana\"}\n" + "{}\n"),
      new BasicHeader("Content-Type", "application/x-ndjso")
    ));
  }

  @Test
  public void testGet() throws Exception {
    generalCheck(ro.performRequest("GET", "/.kibana/doc/1"));
  }

  @Test
  public void testMultiGetDocs() throws Exception {
    generalCheck(ro.performRequest(
      "GET",
      "/_mget",
      Maps.newHashMap(),
      new StringEntity("{\"docs\":[{\"_index\":\".kibana\",\"_type\":\"doc\",\"_id\":\"1\"}]}"),
      new BasicHeader("Content-Type", "application/json")
    ));

  }

  @Test
  public void testMultiGetIds() throws Exception {
    generalCheck(ro.performRequest(
      "GET",
      "/.kibana/_mget",
      Maps.newHashMap(),
      new StringEntity("{\"ids\":[\"1\"]}"),
      new BasicHeader("Content-Type", "application/json")
    ));

  }

  private void generalCheck(Response resp) throws Exception {
    String body = body(resp);
    System.out.println(body);
    assertEquals(200, resp.getStatusLine().getStatusCode());
    assertFalse(body.contains(".kibana_simone"));
    assertTrue(body.contains(".kibana"));
    assertTrue(body.contains("helloWorld"));
  }
}
