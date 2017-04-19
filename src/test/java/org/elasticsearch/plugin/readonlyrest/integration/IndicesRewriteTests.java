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
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesRewriteTests {

  @ClassRule
  public static ESWithReadonlyRestContainer container = create(
    "/indices_rewrite/elasticsearch.yml",
    Optional.of(new ESWithReadonlyRestContainer.ESInitalizer() {
      @Override
      public void initialize(RestClient client) {
        try {
          assertEquals(
            container
              .getBasicAuthClient("simone", "rw_pass")
              .performRequest("PUT", "/.kibana")
              .getStatusLine().getStatusCode(),
            200
          );
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    })
  );


  @Test
  public void testCreateIndex() throws Exception {

    String indicesList = EntityUtils.toString(
      container.getBasicAuthClient("kibana", "kibana")
        .performRequest("GET", "/_cat/indices").getEntity());

    assertTrue(indicesList.contains(".kibana_simone"));
  }

  @Test
  public void testGetFromIndex() throws Exception {
    assertEquals(
      container
        .getBasicAuthClient("simone", "ro_pass")
        .performRequest("GET", "/.kibana/_search")
        .getStatusLine().getStatusCode(),
      200
    );
  }


  @Test
  public void testSearch() throws Exception {
    assertEquals(
      container
        .getBasicAuthClient("simone", "ro_pass")
        .performRequest("GET",
                        "/.kibana/_search"
        )
        .getStatusLine().getStatusCode(),
      200
    );
  }

  @Test
  public void testMultiSearchFromIndex() throws Exception {
    assertEquals(
      container
        .getBasicAuthClient("simone", "ro_pass")
        .performRequest("GET",
                        "/_msearch",
                        Maps.newHashMap(),
                        new StringEntity("{\"index\" : \".kibana\"}\n" + "{}\n"),
                        new BasicHeader("Content-Type", "application/x-ndjso")
        )
        .getStatusLine().getStatusCode(),
      200
    );
  }

}
