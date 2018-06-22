package tech.beshu.ror.integration

import java.util.Optional

import com.mashape.unirest.http.Unirest
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.{ClassRule, Test}
import org.testcontainers.shaded.com.google.common.net.HostAndPort
import tech.beshu.ror.utils.Tuple
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer.ESInitalizer
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.httpclient.RestClient


@RunWith(classOf[BlockJUnit4ClassRunner])
class HostsRuleIntegrationTests {

  import HostsRuleIntegrationTests._

  @Test
  def testGet = {
    useCredentials("blabla", "kibana")
    assertEquals(get, 401)
  }
}

object HostsRuleIntegrationTests {

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
      |  - name: "::BAD HOST::"
      |    accept_x-forwarded-for_header: true
      |    hosts: ["wontresolve"]
      |    indices: ["empty_index"]
      |    kibana_access: rw
      |
    """.stripMargin

  def get = {
    val resp = Unirest.post(url + "_search").asString()
    println("RESPONSE: " + resp.getBody)
    resp.getStatus
  }

  def useCredentials(user: String, pass: String) = Unirest.setHttpClient(
    new RestClient(
      true,
      endpoint.getHostText,
      endpoint.getPort,
      Optional.of(Tuple.from(user, pass))
    ).getUnderlyingClient
  )

  var url: String = null

  @ClassRule def container = ESWithReadonlyRestContainer.create(RorPluginGradleProject.fromSystemProperty,
    TempFile.newFile(getClass.getName, "elasticsearch.yml", settingsYaml),
    Optional.of(new ESInitalizer {
      override def initialize(client: RestClient): Unit = {
        endpoint = HostAndPort.fromParts(client.getHost, client.getPort)

        Unirest.setHttpClient(client.getUnderlyingClient)
        url = client.from("").toASCIIString
        println("Added empty index: " + Unirest.put(url + "empty_index")
          .header("refresh", "wait_for")
          .header("timeout", "50s")
          .asString().getBody)

        println("ES DOCUMENT WRITTEN IN .kibana! " + Unirest.put(url + ".kibana/documents/doc1")
          .header("refresh", "wait_for")
          .header("timeout", "50s")
          .body("""{"id": "asd123"}""")
          .asString().getBody)

        // #TODO Hack the refresh=wait_for is not working, fixing temporarily with this shit
        Thread.sleep(600)

      }
    }))

  var endpoint: HostAndPort = null
}
