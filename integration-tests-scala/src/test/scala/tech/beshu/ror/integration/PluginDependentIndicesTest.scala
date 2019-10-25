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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, ScriptManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

class PluginDependentIndicesTest extends WordSpec with ForAllTestContainer with ESVersionSupport with Matchers {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/plugin_indices/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(nodeDataInitializer = PluginDependentIndicesTest.nodeDataInitializer())
  )

  "Search can be done" when {
    "user uses local auth rule" when {
      "mustache template can be used" excludeES("es51x", "es52x", "es53x")  in {
        val searchManager = new SearchManager(
          container.nodesContainers.head.client("dev1", "test")
        )
        val scriptManager = new ScriptManager(
          container.nodesContainers.head.client("dev1", "test")
        )
        val templateId = "template1"

        val script =
          """
            |{
            |    "script": {
            |        "lang": "mustache",
            |        "source": {
            |            "query": {
            |                "match": {
            |                    "hello": "{{query_string}}"
            |                }
            |            }
            |        }
            |    }
            |}
          """.stripMargin

        val storeResult = scriptManager.store(s"/_scripts/$templateId", script)
        assertEquals(200, storeResult.responseCode)

        val query =
          s"""
             |{
             |    "id": "$templateId",
             |    "params": {
             |        "query_string": "world"
             |    }
             |}
          """.stripMargin
        val result = searchManager.search("/test1_index/_search/template", query)
        result.responseCode shouldEqual 200
        val searchJson = result.searchHits
        val source = searchJson.get(0)("_source")
        source should be(ujson.read("""{"hello":"world"}"""))

      }
    }
  }
}

object PluginDependentIndicesTest {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}") //Test doesn't pass without this line
  }
}