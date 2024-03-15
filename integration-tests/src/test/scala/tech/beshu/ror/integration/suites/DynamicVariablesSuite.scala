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
package tech.beshu.ror.integration.suites

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class DynamicVariablesSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/dynamic_vars/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = {
    def esClusterSettingsCreator(securityType: SecurityType) = {
      EsClusterSettings.create(
        clusterName = "ROR1",
        containerSpecification = ContainerSpecification(
          environmentVariables = Map("TEST_VAR" -> "dev"),
          additionalElasticsearchYamlEntries = Map.empty
        ),
        securityType = securityType,
        nodeDataInitializer = DynamicVariablesSuite.nodeDataInitializer()
      )
    }

    createLocalClusterContainer(
      esNewerOrEqual65ClusterSettings = esClusterSettingsCreator(
        SecurityType.RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName
        ))
      ),
      esOlderThan63ClusterSettings = esClusterSettingsCreator(
        SecurityType.RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName
        ))
      )
    )
  }

  private lazy val searchManager = new SearchManager(basicAuthClient("simone", "dev"), esVersionUsed)

  "A search request" should {
    "be allowed with username as suffix" in {
      val response = searchManager.search(".kibana_simone")

      response should have statusCode 200
      response.searchHits.size should be(1)
      response.searchHits.head("_id").str should be("doc-asd")
    }
  }
}

object DynamicVariablesSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager
      .createDoc(".kibana_simone", "documents", "doc-asd", ujson.read("""{"title": ".kibana_simone"}"""))
      .force()
  }
}