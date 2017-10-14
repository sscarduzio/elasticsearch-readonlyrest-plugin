package tech.beshu.ror.integration

import java.util.Optional

import com.jayway.jsonpath.JsonPath
import com.mashape.unirest.http.{HttpResponse, Unirest}
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.{ClassRule, Test}
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer.ESInitalizer
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.httpclient.RestClient

object TEST1 {
  val NOT_EXISTS =
    """{"index":["perfmon_index_does_not_exist"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin

  val QUERY_WORKS =
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"match_all":{}}, "size":0}
      |""".stripMargin

  val EMPTY_INDEX =
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin
}

@RunWith(classOf[BlockJUnit4ClassRunner])
class MSearchTests {

  import MSearchTests._

  @Test
  def test274_1_notexist() = {
    assertEquals("[0]", msearchRequest(TEST1.NOT_EXISTS))
  }

  @Test
  def test274_1_queryworks() = {
    assertEquals("[1]", msearchRequest(TEST1.QUERY_WORKS))
  }

  @Test
  def test274_1_emptyindex() = {
    assertEquals("[0]", msearchRequest(TEST1.EMPTY_INDEX))
  }

  @Test
  def test274_1_all() = {
    assertEquals("[0,1,0]", msearchRequest(TEST1.EMPTY_INDEX + TEST1.QUERY_WORKS + TEST1.EMPTY_INDEX))
  }
}

object MSearchTests {

  def msearchRequest(body: String) = {
    val response = Unirest.post(url + "_msearch")
      .header("Content-Type", "application/x-ndjson")
      .body(body)
      .asString()
    parseResults(response)
  }

  def parseResults(resp: HttpResponse[String]) = {
    System.out.println("MSEARCH RESPONSE: " + resp.getBody)
    assertEquals(200, resp.getStatus)
    JsonPath.parse(resp.getBody).read("$.responses[*].hits.total").toString
  }

  var url: String = null

  @ClassRule def container = ESWithReadonlyRestContainer.create(RorPluginGradleProject.fromSystemProperty,
    TempFile.newFile(getClass.getName, "elasticsearch.yml",
      """
        |http.bind_host: _eth0:ipv4_
        |network.host: _eth0:ipv4_
        |
        |xpack:
        |  monitoring.enabled: false
        |  security.enabled: false
        |  graph.enabled: false
        |  watcher.enabled: false
        |
        |http.type: ssl_netty4
        |transport.type: local
        |
        |readonlyrest:
        |  ssl:
        |    enable: true
        |    keystore_file: "config/keystore.jks"
        |    keystore_pass: readonlyrest
        |    key_pass: readonlyrest
        |
        |  access_control_rules:
        |
        |  - name: "CONTAINER ADMIN"
        |    type: allow
        |    auth_key: admin:container
        |
        |  - name: "Kibana at user"
        |    type: allow
        |    auth_key: pablo:dev
        |    indices: [".kibana_simone"]
      """.stripMargin),
    Optional.of(new ESInitalizer {
      override def initialize(adminClient: RestClient): Unit = {
        Unirest.setHttpClient(adminClient.getUnderlyingClient)
        url = adminClient.from("").toASCIIString
        Unirest.put(url + ".kibana/documents/doc1")
          .header("refresh", "wait_for")
          .header("timeout", "50s")
          .body("""{"id": "asd123"}""")
          .asString()

        println("ES DOCUMENT WRITTEN IN .kibana! " + Unirest.get(url).asString())
      }
    }))

}
