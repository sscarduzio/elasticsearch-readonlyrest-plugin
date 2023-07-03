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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

class DynamicVariablesSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with Matchers {

  override implicit val rorConfigFileName = "/dynamic_vars/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR1",
      containerSpecification = ContainerSpecification(
        environmentVariables = Map("TEST_VAR" -> "dev"),
        additionalElasticsearchYamlEntries = Map.empty
      ),
      securityType = SecurityType.RorWithXpackSecurity(Attributes.default.copy(
        rorConfigFileName = rorConfigFileName
      )),
      nodeDataInitializer = DynamicVariablesSuite.nodeDataInitializer()
    )
  )

  private lazy val searchManager = new SearchManager(basicAuthClient("simone", "dev"))

  "A search request" should {
    "be allowed with username as suffix" in {
      val response = searchManager.search(".kibana_simone")

      response.responseCode should be(200)
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