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

import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer;
import org.elasticsearch.plugin.readonlyrest.utils.gradle.RorPluginGradleProject;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.Optional;

import static junit.framework.TestCase.assertTrue;
import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;

public class IndexSettingsTests {

  private final static String NEW_CONFIGURATION = "readonlyrest:\\r\\n    access_control_rules:\\r\\n\\r\\n    - name: \\\"CONTAINER ADMIN\\\"\\r\\n      type: allow\\r\\n      auth_key: admin:container\\r\\n\\r\\n    - name: \\\"User 2\\\"\\r\\n      type: allow\\r\\n      auth_key: user2:dev";

  @ClassRule
  public static ESWithReadonlyRestContainer container =
    create(
      RorPluginGradleProject.fromSystemProperty(),
      "/indices_rewrite/elasticsearch.yml",
      Optional.of(
        new ESWithReadonlyRestContainer.ESInitalizer() {
          @Override
          public void initialize(RestClient adminClient) {
            try {
              HttpPut request = new HttpPut(adminClient.from("/.readonlyrest/settings/1"));
              request.setHeader("refresh", "true");
              request.setHeader("timeout", "50s");
              request.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjso");
              request.setEntity(new StringEntity("{\"settings\":\"" + NEW_CONFIGURATION + "\" }"));
              System.out.println(body(adminClient.execute(request)));

            } catch (Exception e) {
              e.printStackTrace();
              throw new IllegalStateException("Cannot configure test case", e);
            }

            try {
              HttpResponse response;
              do {
                HttpHead request = new HttpHead(adminClient.from("/.readonlyrest/settings/1"));
                response = adminClient.execute(request);
                EntityUtils.consume(response.getEntity());
              } while (response.getStatusLine().getStatusCode() != 200);

              HttpPost refreshReq = new HttpPost(adminClient.from("/_readonlyrest/admin/refreshconfig"));
              refreshReq.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjso");
              response = adminClient.execute(refreshReq);
              System.out.println(body(response));
              assert (200 == response.getStatusLine().getStatusCode());

            } catch (Exception e) {
              e.printStackTrace();
              throw new IllegalStateException("Cannot configure test case", e);
            }
          }
        }
      )
    );

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

  @Test
  public void testUser2OK() throws Exception {
    RestClient user2client = getUser2();
    checkOK(user2client.execute(new HttpGet(user2client.from("/_cluster/health"))));
  }
  
  @Test
  public void testUser1KO() throws Exception {
    RestClient user1client = getUser1();
    checkKO(user1client.execute(new HttpGet(user1client.from("/_cluster/health"))));
  }


  private String checkOK(HttpResponse resp) throws Exception {
    assertTrue(resp.getStatusLine().getStatusCode() <= 201);
    return EntityUtils.toString(resp.getEntity());
  }

  private String checkKO(HttpResponse resp) throws Exception {
    assertTrue(resp.getStatusLine().getStatusCode() > 299);
    return EntityUtils.toString(resp.getEntity());
  }

  private RestClient getUser1() {
    return container.getBasicAuthClient("user1", "dev");
  }

  private RestClient getUser2() {
    return container.getBasicAuthClient("user2", "dev");
  }

}
