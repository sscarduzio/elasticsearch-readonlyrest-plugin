package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManager, DocumentManager}

class ActionsTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/actions/readonlyrest.yml",
    numberOfInstances = 1,
    ActionsTests.nodeDataInitializer()
  )

  private lazy val actionManager = new ActionManager(container.nodesContainers.head.client("any", "whatever"))

  "A actions rule" should {
    "work for delete request" which {
      "forbid deleting from test1_index" in {
        val result = actionManager.actionDelete("test1_index/test/1")
        assertEquals(401, result.getResponseCode)
      }
      "allow deleting from test2_index" in {
        val result = actionManager.actionDelete("test2_index/test/1")
        assertEquals(200, result.getResponseCode)
      }
    }
  }
}

object ActionsTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}
