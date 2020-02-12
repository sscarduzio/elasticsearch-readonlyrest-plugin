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
class MSearchTEST1_Tests {

  import MSearchTEST1_Tests._

  @Test
  def test274_1_notexist() = {
    useCredentials("kibana", "kibana")
    msearchFailedRequest(TEST1.MSEAERCH_BODY_NOT_EXISTS)
  }

  @Test
  def test274_1_queryworks() = {
    useCredentials("kibana", "kibana")
    assertEquals("[1]", msearchSuccessRequest(TEST1.MSEAERCH_BODY_QUERY_WORKS))
  }

  @Test
  def test274_1_empty_index() = {
    useCredentials("kibana", "kibana")
    assertEquals("[0]", msearchSuccessRequest(TEST1.MSEAERCH_BODY_EMPTY_INDEX))
  }

  @Test
  def test274_1_all() = {
    useCredentials("kibana", "kibana")
    assertEquals("[0,1,0]", msearchSuccessRequest(TEST1.MSEAERCH_BODY_COMBO))
  }
}

object MSearchTEST1_Tests {

  object TEST1 {
    val MSEAERCH_BODY_NOT_EXISTS =
      """{"index":["perfmon_index_does_not_exist"],"ignore_unavailable":true,"preference":1506497937939}
        |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
        |""".stripMargin

    val MSEAERCH_BODY_QUERY_WORKS =
      """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
        |{"query":{"match_all":{}}, "size":0}
        |""".stripMargin

    val MSEAERCH_BODY_EMPTY_INDEX =
      """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
        |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
        |""".stripMargin

    val settingsYaml =
      """
        |http.bind_host: _eth0:ipv4_
        |network.host: _eth0:ipv4_
        |
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
        |    indices: [".kibana"]
        |    verbosity: error
      """.stripMargin

    val MSEAERCH_BODY_COMBO = MSEAERCH_BODY_NOT_EXISTS + MSEAERCH_BODY_QUERY_WORKS + MSEAERCH_BODY_EMPTY_INDEX
  }

  def useCredentials(user: String, pass: String) = Unirest.setHttpClient(
    new RestClient(
      true,
      endpoint.getHostText,
      endpoint.getPort,
      Optional.of(Tuple.from(user, pass))
    ).getUnderlyingClient
  )

  def msearchSuccessRequest(body: String) = {
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

  def msearchFailedRequest(body: String) = {
    val resp = Unirest.post(url + "_msearch")
      .header("Content-Type", "application/x-ndjson")
      .body(body)
      .asString()
    System.out.println("MSEARCH RESPONSE: " + resp.getBody)
    assertEquals(200, resp.getStatus)
    val jsonPath = JsonPath.parse(resp.getBody)
    val result = jsonPath.read("$.responses[*].hits.total.value").toString
    val total = if(result == "[]") jsonPath.read("$.responses[*].hits.total").toString
    else result
    assertEquals(total, "[0]")
  }

  var url: String = null

  @ClassRule def container = ESWithReadonlyRestContainer.create(RorPluginGradleProjectJ.fromSystemProperty,
    TempFile.newFile(getClass.getName, "elasticsearch.yml", TEST1.settingsYaml),
    Optional.of(new ESInitalizer {
      override def initialize(client: RestClient): Unit = {
        endpoint = HostAndPort.fromParts(client.getHost, client.getPort)

        Unirest.setHttpClient(client.getUnderlyingClient)
        url = client.from("").toASCIIString
        println("Added empty index: " +
          Unirest.put(url + "empty_index")
            .header("refresh", "wait_for")
            .header("timeout", "50s")
            .asString().getBody)

        println("ES DOCUMENT WRITTEN IN .kibana! " +
          Unirest.put(url + ".kibana/documents/doc1")
            .header("refresh", "wait_for")
            .header("Content-Type", "application/json")
            .header("timeout", "50s")
            .body("""{"id": "asd123"}""")
            .asString().getBody)

        // #TODO Hack the refresh=wait_for is not working, fixing temporarily with this shit
        Thread.sleep(1000)

      }
    }))

  var endpoint: HostAndPort = null
}
