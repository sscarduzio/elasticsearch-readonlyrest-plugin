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
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

class PluginDependentIndicesTest extends WordSpec  with ForAllTestContainer with Matchers{
  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/plugin_indices/readonlyrest.yml",
    numberOfInstances = 1,
    PluginDependentIndicesTest.nodeDataInitializer()
  )
  "Search can be done" when {
    "user uses local auth rule" when {
      "mustache template can be used" in {
        val searchManager = new SearchManager(
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
            |
            |
          """.stripMargin

        val storeResult = searchManager.storeScript(templateId, script)
        assertEquals(200, storeResult.getResponseCode)

        val query =
          s"""
             |{
             |    "id": "$templateId",
             |    "params": {
             |        "query_string": "world"
             |    }
             |}
          """.stripMargin
        val result = searchManager.templateSearch(query, List("test1_index").asJava)
        result.getResponseCode shouldEqual 200
        result.getSearchHits should have size 1

      }
    }
  }
}

object PluginDependentIndicesTest {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}") //Test doesn't pass without this line
  }
}