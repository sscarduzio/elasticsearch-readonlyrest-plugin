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

import com.google.common.collect.ImmutableMap;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Optional;

import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class LocalGroupsTest {

  private static final String matchingEndpoint = "/_cat/nodes";

  @ClassRule
  public static final ESWithReadonlyRestContainer container = create("/local_groups/elasticsearch.yml", Optional.empty());
  
  private HttpResponse mkRequest(String user, String pass, String endpoint) throws Exception {
    RestClient rcl = container.getBasicAuthClient(user, pass);
    return rcl.execute(new HttpGet(rcl.from(
      endpoint,
      new ImmutableMap.Builder<String, String>()
        .build()
    )));
  }

  @Test
  public void testOK_GoodCredsWithGoodRule() throws Exception {
    assertEquals(
      200,
      mkRequest("user", "passwd", matchingEndpoint).getStatusLine().getStatusCode()
    );
  }

  @Test
  public void testFail_BadCredsGoodRule() throws Exception {
    assertNotEquals(
      200,
      mkRequest("user", "wrong", matchingEndpoint).getStatusLine().getStatusCode()
    );
  }

  @Test
  public void testFail_BadCredsBadRule() throws Exception {
    assertNotEquals(
      mkRequest("user", "wrong", "/_cat/indices").getStatusLine().getStatusCode(),
      200
    );
  }

  @Test
  public void testFail_GoodCredsBadRule() throws Exception {
    assertNotEquals(
      200,
      mkRequest("user", "passwd", "/_cat/indices").getStatusLine().getStatusCode()
    );
  }
}
