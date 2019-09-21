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