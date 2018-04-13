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

import com.google.common.collect.ImmutableMap;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProject;
import tech.beshu.ror.utils.httpclient.RestClient;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Optional;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class LocalGroupsTest {

  private static final String matchingEndpoint = "/_nodes/local";

  @ClassRule
  public static ESWithReadonlyRestContainer container =
    ESWithReadonlyRestContainer.create(
      RorPluginGradleProject.fromSystemProperty(),
      "/local_groups/elasticsearch.yml",
      Optional.empty()
    );

  @Test
  public void testOK_GoodCredsWithGoodRule() throws Exception {
    HttpResponse r = mkRequest("user", "passwd", matchingEndpoint);

    assertEquals(
      200,
      r.getStatusLine().getStatusCode()
    );

    // Piggy back response headers testing (too long to spin up another container)
    assertTrue(r.getHeaders("x-rr-user")[0].getValue().equals("user"));
    assertTrue(r.getHeaders("x-ror-kibana_index")[0].getValue().equals(".kibana_user"));
    assertTrue(r.getHeaders("x-kibana-hide-apps")[0].getValue().equals("timelion"));
    assertTrue(r.getHeaders("x-ror-kibana_access")[0].getValue().equals("admin"));
    assertTrue(r.getHeaders("x-ror-current-group")[0].getValue().equals("testgroup"));
    assertTrue(r.getHeaders("x-ror-available-groups")[0].getValue().equals("testgroup,extra_group"));
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
      200,
      mkRequest("user", "wrong", "/_cat/indices").getStatusLine().getStatusCode()
    );
  }

  @Test
  public void testFail_GoodCredsBadRule() throws Exception {
    assertNotEquals(
      200,
      mkRequest("user", "passwd", "/_cat/indices").getStatusLine().getStatusCode()
    );
  }

  private HttpResponse mkRequest(String user, String pass, String endpoint) throws Exception {
    RestClient rcl = container.getBasicAuthClient(user, pass);
    return rcl.execute(new HttpGet(rcl.from(
      endpoint,
      new ImmutableMap.Builder<String, String>()
        .build()
    )));
  }

}
