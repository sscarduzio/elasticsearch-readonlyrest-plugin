package tech.beshu.ror.integration

import java.util.{Map => JMap}
import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

class FieldLevelSecurityTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/field_level_security/readonlyrest.yml",
    numberOfInstances = 1,
    nodeDataInitializer = FieldLevelSecurityTests.nodeDataInitializer()
  )

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("user1", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.getResponseCode)
        result.getSearchHits.size() should be(1)
        val source = result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]]
        source.size should be(1)
        source.get("dummy2") shouldBe "true"
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("user2", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.getResponseCode)
        result.getSearchHits.size() should be(1)
        val source = result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]]
        source.size should be(1)
        source.get("dummy2") shouldBe "true"
      }
      "blacklist mode is used" in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("user3", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.getResponseCode)
        result.getSearchHits.size() should be(1)
        val source = result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]]
        source.size should be(1)
        source.get("dummy") should be("a1")
      }
      "blacklist mode with wildcard is used" in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("user4", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.getResponseCode)
        result.getSearchHits.size() should be(1)
        val source = result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]]
        source.size should be(1)
        source.get("dummy") should be("a1")
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(container.nodesContainers.head.client("user1", "pass"))

        val result = searchManager.search("/nestedtest/_search")

        assertEquals(200, result.getResponseCode)
        result.getSearchHits.size() should be(1)
        val source = result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]]
        source.size should be(1)
        source.get("dummy2") shouldBe "true"
      }
      "whitelist mode with wildcard is used" in {

      }
      "blacklist mode is used" in {

      }
      "blacklist mode with wildcards is used" in {

      }
    }
  }
}

object FieldLevelSecurityTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/testfiltera/documents/doc-a1", "{\"dummy\":\"a1\", \"dummy2\": \"true\"}")
    documentManager.insertDoc(
      "/nestedtest/documents/1",
      "{" +
        "\"id\":1," +
        "\"items\": [" +
        "{\"itemId\": 1, \"text\":\"text1\",\"startDate\":\"2019-05-22\",\"endDate\":\"2019-07-31\"}," +
        "{\"itemId\": 2, \"text\":\"text2\",\"startDate\":\"2019-05-22\",\"endDate\":\"2019-06-30\"}," +
        "{\"itemId\": 3, \"text\":\"text3\",\"startDate\":\"2019-05-22\",\"endDate\":\"2019-09-30\"}" +
        "]," +
        "\"secrets\": [" +
        "{\"key\":1, \"text\":\"secret1\"}," +
        "{\"key\":2, \"text\":\"secret2\"}" +
        "]" +
        "}"
    )
  }
}