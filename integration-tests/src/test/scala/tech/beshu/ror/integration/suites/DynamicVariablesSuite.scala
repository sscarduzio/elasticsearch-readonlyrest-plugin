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

import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ContainerSpecification, ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait DynamicVariablesSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/dynamic_vars/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      rorContainerSpecification = ContainerSpecification(Map("TEST_VAR" -> "dev")),
      nodeDataInitializer = DynamicVariablesSuite.nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  private lazy val searchManager = new SearchManagerJ(basicAuthClient("simone", "dev"))

  "A search request" should {
    "be allowed with username as suffix" in {
      val response = searchManager.search("/.kibana_simone/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
      response.getSearchHits.get(0).get("_id") should be("doc-asd")
    }
  }
}

object DynamicVariablesSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh("/.kibana_simone/documents/doc-asd", """{"title": ".kibana_simone"}""")
  }
}