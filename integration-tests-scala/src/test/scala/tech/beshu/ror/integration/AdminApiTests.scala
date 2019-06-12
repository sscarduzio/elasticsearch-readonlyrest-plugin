package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManager, DocumentManager}
import tech.beshu.ror.utils.misc.Resources

class AdminApiTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    numberOfInstances = 1,
    AdminApiTests.nodeDataInitializer()
  )

  private lazy val adminActionManager = new ActionManager(container.nodesContainers.head.adminClient)

  "An admin REST API" should {
    "allow admin to force reload current settings" in {
      val result  = adminActionManager.action("_readonlyrest/admin/refreshconfig")
      result.getResponseCode should be (200)
    }
    "provide update index configuration method" which {
      "updates index config when passed config is correct" in {
        val result  = adminActionManager.action(
          "_readonlyrest/admin/config",
          s"""{ "settings": "${Resources.getResourceContent("/admin_api/readonlyrest_to_update.yml")}"}"""
        )
        result.getResponseCode should be (200)
      }
      "not allow to update index configuration" when {
        "passed config is malformed" in {

        }
      }
    }
    "get content of file config" in {

    }
    "get content of index config" in {

    }
  }

}


object AdminApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }

  private def reindexPayload(indexName: String) = {
    s"""{"source":{"index":"$indexName"},"dest":{"index":"${indexName}_reindexed"}}"""
  }
}