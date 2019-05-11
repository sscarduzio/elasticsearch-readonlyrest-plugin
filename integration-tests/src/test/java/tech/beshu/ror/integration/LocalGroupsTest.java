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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.ClassRule;
import org.junit.Test;
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer;
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class LocalGroupsTest {

  private static final String matchingEndpoint = "/_nodes/local";

  @ClassRule
  public static ESWithReadonlyRestContainer container =
      ESWithReadonlyRestContainer.create(
          RorPluginGradleProjectJ.fromSystemProperty(), "/local_groups/elasticsearch.yml",
          Optional.empty()
      );

  @Test
  public void testOK_GoodCredsWithGoodRule() throws Exception {
    HttpResponse r = mkRequest("user", "passwd", matchingEndpoint);

    assertEquals(
        200,
        r.getStatusLine().getStatusCode()
    );

    // Piggy back response headers testing (too long to spin up another multiContainerDependent)
    assertEquals("user", r.getHeaders("x-ror-username")[0].getValue());
    assertEquals(".kibana_user", r.getHeaders("x-ror-kibana_index")[0].getValue());
    assertEquals("timelion", r.getHeaders("x-ror-kibana-hidden-apps")[0].getValue());
    assertEquals("admin", r.getHeaders("x-ror-kibana_access")[0].getValue().toLowerCase());
    assertEquals("testgroup", r.getHeaders("x-ror-current-group")[0].getValue());
    assertEquals("extra_group,foogroup,testgroup", r.getHeaders("x-ror-available-groups")[0].getValue());
  }

  @Test
  public void testOK_GoodCredsWithGoodRuleWithMatchingPreferredgroup() throws Exception {
    HttpResponse r = mkRequest("user", "passwd", matchingEndpoint, "foogroup");

    assertEquals(
        200,
        r.getStatusLine().getStatusCode()
    );

    // Piggy back response headers testing (too long to spin up another multiContainerDependent)
    assertEquals("user", r.getHeaders("x-ror-username")[0].getValue());
    assertEquals(".kibana_foogroup", r.getHeaders("x-ror-kibana_index")[0].getValue());
    assertEquals("foo:app", r.getHeaders("x-ror-kibana-hidden-apps")[0].getValue());
    assertEquals("admin", r.getHeaders("x-ror-kibana_access")[0].getValue().toLowerCase());
    assertEquals("foogroup", r.getHeaders("x-ror-current-group")[0].getValue());
    assertEquals("extra_group,foogroup,testgroup", r.getHeaders("x-ror-available-groups")[0].getValue());
  }

  @Test
  public void testOK_GoodCredsWithGoodRuleWithNoNMatchingPreferredGroup() throws Exception {
    HttpResponse r = mkRequest("user", "passwd", matchingEndpoint, "extra_group");

    assertEquals(
        401,
        r.getStatusLine().getStatusCode()
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
        200,
        mkRequest("user", "wrong", "/_cat/indices").getStatusLine().getStatusCode()
    );
  }

  @Test
  public void testFail_GoodCredsBadRule() throws Exception {
    assertNotEquals(
        200,
        mkRequest("user", "passwd", "/_search").getStatusLine().getStatusCode()
    );
  }

  @Test
  public void testIdentityRetrieval() throws Exception {

    HttpResponse response = mkRequest("user", "passwd", "/_readonlyrest/metadata/current_user");
    assertEquals(response.getStatusLine().getStatusCode(), 200);
    Type type = new TypeToken<Map<String, Object>>() {
    }.getType();

    String body = EntityUtils.toString(response.getEntity());
    System.out.println("identity object: " + body);
    Map<String, Object> bodyMap = new Gson().fromJson(body, type);
    assertEquals(".kibana_user", bodyMap.get("x-ror-kibana_index"));
    assertEquals("user", bodyMap.get("x-ror-username"));
    assertEquals("[timelion]", bodyMap.get("x-ror-kibana-hidden-apps").toString());
    assertEquals("admin", bodyMap.get("x-ror-kibana_access").toString().toLowerCase());
    assertEquals("testgroup", bodyMap.get("x-ror-current-group"));
    assertTrue(bodyMap.get("x-ror-available-groups").toString().contains("testgroup"));
    assertTrue(bodyMap.get("x-ror-available-groups").toString().contains("extra_group"));
    assertTrue(bodyMap.get("x-ror-available-groups").toString().contains("foogroup"));

  }

  private HttpResponse mkRequest(String user, String pass, String endpoint) throws Exception {
    return mkRequest(user, pass, endpoint, null);
  }

  private HttpResponse mkRequest(String user, String pass, String endpoint, String preferredGroup) throws Exception {
    RestClient rcl = container.getBasicAuthClient(user, pass);
    HttpGet req = new HttpGet(rcl.from(
        endpoint,
        new ImmutableMap.Builder<String, String>()
            .build()
    ));

    if (!Strings.isNullOrEmpty(preferredGroup)) {
      req.setHeader("x-ror-current-group", preferredGroup);
    }
    return rcl.execute(req);
  }

}
