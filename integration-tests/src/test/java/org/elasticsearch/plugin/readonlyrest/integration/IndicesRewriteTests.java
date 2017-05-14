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
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.HttpGetWithEntity;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;
import org.junit.Rule;
import org.junit.Test;

import java.util.Optional;

import static junit.framework.TestCase.assertFalse;
import static org.elasticsearch.plugin.readonlyrest.utils.containers.ESWithReadonlyRestContainer.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndicesRewriteTests extends BaseIntegrationTests {

  @Rule
  public final ESWithReadonlyRestContainer container;
  private RestClient ro;
  private RestClient rw;
  private RestClient kibana;
  private RestClient logstash;

  public IndicesRewriteTests(String esProject) {
    this.container = create(
        new RorPluginGradleProject(esProject),
        "/indices_rewrite/elasticsearch.yml",
        Optional.of(
            new ESWithReadonlyRestContainer.ESInitalizer() {
              @Override
              public void initialize(RestClient client) {
                ro = container.getBasicAuthClient("simone", "ro_pass");
                rw = container.getBasicAuthClient("simone", "rw_pass");
                kibana = container.getBasicAuthClient("kibana", "kibana");
                logstash = container.getBasicAuthClient("simone", "logstash");

                try {
                  HttpPut request = new HttpPut(client.from("/.kibana_simone/doc/1"));
                  request.setHeader("refresh", "true");
                  request.setHeader("timeout", "50s");
                  request.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjso");
                  request.setEntity(new StringEntity("{\"helloWorld\": true}"));
                  System.out.println(body(kibana.execute(request)));
                } catch (Exception e) {
                  e.printStackTrace();
                  throw new IllegalStateException("Cannot configure test case", e);
                }

                try {
                  HttpResponse response;
                  do {
                    HttpHead request = new HttpHead(client.from("/.kibana_simone/doc/1"));
                    response = kibana.execute(request);
                    EntityUtils.consume(response.getEntity());
                  } while (response.getStatusLine().getStatusCode() != 200);
                } catch (Exception e) {
                  e.printStackTrace();
                  throw new IllegalStateException("Cannot configure test case", e);
                }
              }
            }
        ));
  }

  @Test
  public void testNonIndexRequest() throws Exception {
    checkForErrors(kibana.execute(new HttpGet(kibana.from("/"))));
    checkForErrors(kibana.execute(new HttpGet(kibana.from("/_cluster/health"))));
  }

  @Test
  public void testCreateIndex() throws Exception {
    String indicesList = body(kibana.execute(new HttpGet(kibana.from("/_cat/indices"))));
    assertTrue(indicesList.contains(".kibana_simone"));
  }

  @Test
  public void testSearch() throws Exception {
    HttpGet request = new HttpGet(ro.from("/.kibana/_search"));
    checkKibanaResponse(ro.execute(request));
  }

  @Test
  public void testMultiSearch() throws Exception {
    HttpGetWithEntity request = new HttpGetWithEntity(ro.from("/_msearch"));
    request.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-ndjso");
    request.setEntity(new StringEntity("{\"index\" : \".kibana\"}\n" + "{}\n"));
    checkKibanaResponse(ro.execute(request));
  }

  @Test
  public void testGet() throws Exception {
    checkKibanaResponse(ro.execute(new HttpGet(ro.from("/.kibana/doc/1"))));
  }

  @Test
  public void testMultiGetDocs() throws Exception {
    HttpGetWithEntity request = new HttpGetWithEntity(ro.from("/_mget"));
    request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    request.setEntity(new StringEntity("{\"docs\":[{\"_index\":\".kibana\",\"_type\":\"doc\",\"_id\":\"1\"}]}"));
    checkKibanaResponse(ro.execute(request));
  }

  @Test
  public void testMultiGetIds() throws Exception {
    HttpGetWithEntity request = new HttpGetWithEntity(ro.from("/.kibana/_mget"));
    request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    request.setEntity(new StringEntity("{\"ids\":[\"1\"]}"));
    checkKibanaResponse(ro.execute(request));
  }

  @Test
  public void testBulkAll() throws Exception {
    HttpPost request = new HttpPost(logstash.from("/_bulk"));
    request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    request.setEntity(
        new StringEntity("{ \"index\" : { \"_index\" : \"logstash-2017-01-01\", \"_type\" : \"doc\", \"_id\" : \"2\" } }\n" +
            "{ \"helloWorld\" : false }\n" +
            "{ \"delete\" : { \"_index\" : \"logstash-2017-01-01\", \"_type\" : \"doc\", \"_id\" : \"2\" } }\n" +
            "{ \"create\" : { \"_index\" : \"logstash-2017-01-01\", \"_type\" : \"doc\", \"_id\" : \"3\" } }\n" +
            "{ \"helloWorld\" : false }\n" +
            "{ \"update\" : {\"_id\" : \"2\", \"_type\" : \"doc\", \"_index\" : \"logstash-2017-01-01\"} }\n")
    );
    checkForErrors(logstash.execute(request));
  }

  @Test
  public void testCreateIndexPattern() throws Exception {
    HttpPost request = new HttpPost(rw.from("/.kibana/index-pattern/logstash-*/_create"));
    request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    request.setEntity(
        new StringEntity("{\"title\":\"logstash-*\",\"timeFieldName\":\"@timestamp\"}\n")
    );
    HttpResponse resp = rw.execute(request);
    String body = checkForErrors(resp);
    assertTrue(body.contains("\"_index\":\".kibana\""));
  }

  private void checkKibanaResponse(HttpResponse resp) throws Exception {
    String body = body(resp);
    System.out.println(body);
    assertEquals(200, resp.getStatusLine().getStatusCode());
    assertFalse(body.contains(".kibana_simone"));
    assertTrue(body.contains(".kibana"));
    assertTrue(body.contains("helloWorld"));
  }

  private String checkForErrors(HttpResponse resp) throws Exception {
    assertTrue(resp.getStatusLine().getStatusCode() <= 201);
    return EntityUtils.toString(resp.getEntity());
  }

  private static String body(HttpResponse r) throws Exception {
    return EntityUtils.toString(r.getEntity());
  }

}
