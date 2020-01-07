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
package tech.beshu.ror.integration

import java.util.Optional

import com.jayway.jsonpath.JsonPath
import com.mashape.unirest.http.Unirest
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.{ClassRule, Test}
import org.testcontainers.shaded.com.google.common.net.HostAndPort
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer.ESInitalizer
import tech.beshu.ror.utils.gradle.RorPluginGradleProjectJ
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{TempFile, Tuple}

@RunWith(classOf[BlockJUnit4ClassRunner])
class MSearchTEST2_Tests {
  import MSearchTEST2_Tests._

  @Test
  def test274_2_nomatch() = {
    useCredentials("kibana", "kibana")
    assertEquals("[0]", msearchRequest(TEST2.MSEAERCH_NO_MATCH))
  }

  @Test
  def test274_2_broken() = {
    useCredentials("kibana", "kibana")
    assertEquals("[1]", msearchRequest(TEST2.MSEAERCH_BROKEN))
  }

  @Test
  def test274_2_empty_index() = {
    useCredentials("kibana", "kibana")
    assertEquals("[0]", msearchRequest(TEST2.MSEAERCH_BODY_EMPTY_INDEX))
  }

  @Test
  def test274_2_combo() = {
    useCredentials("kibana", "kibana")
    assertEquals("[0,1,0]", msearchRequest(TEST2.MSEAERCH_BODY_COMBO))
  }
}

object MSearchTEST2_Tests {

  object TEST2 {
    val MSEAERCH_NO_MATCH =
      """{"index":["perfmon_logstash-apacheaccess*"]}
        |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
        |""".stripMargin

    val MSEAERCH_BROKEN =
      """{"index":["perfmon_endpoint_requests"]}
        |{"query":{"query_string":{"analyze_wildcard":true,"query":"*"}},"size":0}
        |""".stripMargin

    val MSEAERCH_BODY_EMPTY_INDEX =
      """{"index":["empty_index"],"ignore_unavailable":true,"preference":1506497937939}
        |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
        |""".stripMargin

    val settingsYaml =
      """
        |http.bind_host: _eth0:ipv4_
        |network.host: _eth0:ipv4_
        |http.type: ssl_netty4
        |#transport.type: local
        |
        |readonlyrest:
        |  ssl:
        |    enable: true
        |    keystore_file: "keystore.jks"
        |    keystore_pass: readonlyrest
        |    key_pass: readonlyrest
        |
        |  access_control_rules:
        |
        |  - name: "CONTAINER ADMIN"
        |    type: allow
        |    auth_key: admin:container
        |
        |  - name: "::KIBANA-SRV::"
        |    auth_key: kibana:kibana
        |    indices: ["empty_index", "perfmon_endpoint_requests","perfmon_logstash-apacheaccess*"]
        |    verbosity: error
      """.stripMargin

    val MSEAERCH_BODY_COMBO = MSEAERCH_NO_MATCH + MSEAERCH_BROKEN + MSEAERCH_BODY_EMPTY_INDEX
  }

  def useCredentials(user: String, pass: String) = Unirest.setHttpClient(
    new RestClient(
      true,
      endpoint.getHostText,
      endpoint.getPort,
      Optional.of(Tuple.from(user, pass))
    ).getUnderlyingClient
  )

  def msearchRequest(body: String) = {
    val resp = Unirest.post(url + "_msearch")
      .header("Content-Type", "application/x-ndjson")
      .body(body)
      .asString()
    System.out.println("MSEARCH RESPONSE: " + resp.getBody)
    assertEquals(200, resp.getStatus)
    val jsonPath = JsonPath.parse(resp.getBody)
    val result = jsonPath.read("$.responses[*].hits.total.value").toString
    if(result == "[]") jsonPath.read("$.responses[*].hits.total").toString
    else result
  }

  var url: String = null

  @ClassRule def container = ESWithReadonlyRestContainer.create(RorPluginGradleProjectJ.fromSystemProperty,
    TempFile.newFile(getClass.getName, "elasticsearch.yml", TEST2.settingsYaml),
    Optional.of(new ESInitalizer {
      override def initialize(client: RestClient): Unit = {
        endpoint = HostAndPort.fromParts(client.getHost, client.getPort)


        // Creating an empty index
        Unirest.setHttpClient(client.getUnderlyingClient)
        url = client.from("").toASCIIString
        println("Added empty index: " + Unirest.put(url + "empty_index")
          .header("refresh", "wait_for")
          .header("timeout", "50s")
          .asString().getBody)

        // Create "perfmon_endpoint_requests" index with 1 doc in it
        println("ES DOCUMENT WRITTEN IN perfmon_endpoint_requests! " + Unirest.put(url + "perfmon_endpoint_requests/documents/doc1")
          .header("refresh", "wait_for")
          .header("timeout", "50s")
          .header("Content-Type", "application/json")
          .body("""{"id": "asd123"}""")
          .asString().getBody)

        // Create "perfmon_logstash-apacheaccess1" index with 2 doc in it
        println("ES DOCUMENT WRITTEN IN perfmon_logstash-apacheaccess1 (1/2)! " + Unirest.put(url + "perfmon_logstash-apacheaccess1/documents/doc1")
          .header("refresh", "wait_for")
          .header("Content-Type", "application/json")
          .header("timeout", "50s")
          .body("""{"id": "asd123"}""")
          .asString().getBody)

        println("ES DOCUMENT WRITTEN IN perfmon_logstash-apacheaccess1 (2/2)! " + Unirest.put(url + "perfmon_logstash-apacheaccess1/documents/doc2")
          .header("refresh", "wait_for")
          .header("Content-Type", "application/json")
          .header("timeout", "50s")
          .body("""{"id": "asd123"}""")
          .asString().getBody)

        // #TODO Hack the refresh=wait_for is not working, fixing temporarily with this shit
        Thread.sleep(3000)

      }
    }))

  var endpoint: HostAndPort = null
}
