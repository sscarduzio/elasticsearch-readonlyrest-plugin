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
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, ScriptManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

class MustacheLangTests extends WordSpec with ForAllTestContainer with ESVersionSupport with Matchers {

  override lazy val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/plugin_indices/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(
      nodeDataInitializer = MustacheLangTests.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  "Search can be done" when {
    "user uses local auth rule" when {
      "mustache template can be used" excludeES("es51x", "es52x", "es53x") in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("dev1", "test"))
        val query =
          s"""
             |{
             |    "id": "template1",
             |    "params": {
             |        "query_string": "world"
             |    }
             |}
          """.stripMargin
        val result = searchManager.search("/test1_index/_search/template", query)

        result.responseCode shouldEqual 200
        result.searchHits.get(0)("_source") should be(ujson.read("""{"hello":"world"}"""))
      }
    }
  }
  "Template rendering can be done" when {
    "user uses local auth rule" excludeES("es51x", "es52x", "es53x") in {
      val searchManager = new SearchManager(container.nodesContainers.head.client("dev1", "test"))

      val result = searchManager.renderTemplate(
        s"""
           |{
           |    "id": "template1",
           |    "params": {
           |        "query_string": "world"
           |    }
           |}
          """.stripMargin
      )

      result.responseCode shouldEqual 200
      result.body should be ("""{"template_output":{"query":{"match":{"hello":"world"}}}}""")
    }
  }
}

object MustacheLangTests {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh("/test1_index/test/1", "{\"hello\":\"world\"}")

    val scriptManager = new ScriptManager(adminRestClient)
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
    val storeResult = scriptManager.store(s"/_scripts/template1", script)
  }
}