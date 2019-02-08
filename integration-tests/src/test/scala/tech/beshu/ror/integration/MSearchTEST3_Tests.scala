package tech.beshu.ror.integration

import java.util.Optional

import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.{ClassRule, Test}
import org.testcontainers.shaded.com.google.common.net.HostAndPort
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer
import tech.beshu.ror.utils.containers.ESWithReadonlyRestContainer.ESInitalizer
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.httpclient.RestClient


@RunWith(classOf[BlockJUnit4ClassRunner])
class MSearchTEST3_Tests {

  import MSearchTEST3_Tests._

  @Test
  def testMgetWildcard() = {
    restUtils.useCredentials("justOverrideAdminCredentials", "random09310+23")
    assertEquals("[1]", restUtils.msearchRequest(TEST3.MSEARCH_BODY_TRY_MATCH_BOTH, Map("X-Forwarded-For" -> "es-timber-hammercloud")))
  }
}

object MSearchTEST3_Tests {

  object TEST3 {
    val MSEARCH_BODY_TRY_MATCH_BOTH =
      """{"index":["monit_private*"]}
        |{"version":true,"size":0,"query":{"match_all":{}}}
        |""".stripMargin


    val settingsYaml =
      """
        |http.bind_host: _eth0:ipv4_
        |network.host: _eth0:ipv4_
        |http.type: ssl_netty4
        |transport.type: local
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
        |  - name: es-timber-hammercloud writer
        |    x_forwarded_for:
        |    - es-timber-hammercloud
        |    type: allow
        |    indices:
        |    - monit_private_hammercloud_*
      """.stripMargin

  }


  var restUtils: RestTestUtils = null


  @ClassRule def container = ESWithReadonlyRestContainer.create(RorPluginGradleProject.fromSystemProperty,
    TempFile.newFile(getClass.getName, "elasticsearch.yml", TEST3.settingsYaml),
    Optional.of(new ESInitalizer {


      override def initialize(client: RestClient): Unit = {
        restUtils = new RestTestUtils(client, HostAndPort.fromParts(client.getHost, client.getPort))

        restUtils.writeDocument("monit_private_hammercloud_2", "docHC2")

        restUtils.writeDocument("monit_private_openshift", "docOS")

        // #TODO Hack the refresh=wait_for is not working, fixing temporarily with this shit
        Thread.sleep(1000)

      }
    }))

}
