package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import scala.collection.JavaConverters._

class ImpersonationTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/impersonation/readonlyrest.yml",
    numberOfInstances = 1,
    ImpersonationTests.nodeDataInitializer()
  )

  "Impersonation can be done" when {
    "user uses local auth rule" when {
      "impersonator can be properly authenticated" in {
        val searchManager = new SearchManager(
          container.nodesContainers.head.client("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test1_index/_search")

        assertEquals(200, result.getResponseCode)
      }
    }
  }
  "Impersonation cannot be done" when {
    "there is no such user with admin privileges" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("unknown", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getResults.size())
      result.getResults.get(0).asScala("reason") should be ("forbidden")
      result.getResults.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "user with admin privileges cannot be authenticated" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("admin1", "wrong_pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getResults.size())
      result.getResults.get(0).asScala("reason") should be ("forbidden")
      result.getResults.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "admin user is authenticated but cannot impersonate given user" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("admin2", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getResults.size())
      result.getResults.get(0).asScala("reason") should be ("forbidden")
      result.getResults.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "not supported authentication rule is used" which {
      "is full hashed auth credentials" in {
        val searchManager = new SearchManager(
          container.nodesContainers.head.client("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test2_index/_search")
        assertEquals(401, result.getResponseCode)
        assertEquals(1, result.getResults.size())
        result.getResults.get(0).asScala("reason") should be ("forbidden")
        result.getResults.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_SUPPORTED")
      }
    }
  }
}

object ImpersonationTests {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}