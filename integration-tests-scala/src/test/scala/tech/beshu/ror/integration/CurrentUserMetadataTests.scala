package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.CurrentUserMetadataManager
import scala.collection.JavaConverters._

class CurrentUserMetadataTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/current_user_metadata/readonlyrest.yml",
    numberOfInstances = 1
  )

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val user1MetadataManager = new CurrentUserMetadataManager(container.nodesContainers.head.client("user1", "pass"))

          val result = user1MetadataManager.fetchMetadata()

          assertEquals(200, result.getResponseCode)
          result.getResponseJson.size() should be (3)
          result.getResponseJson.get("x-ror-username") should be("user1")
          result.getResponseJson.get("x-ror-current-group") should be("group1")
          result.getResponseJson.get("x-ror-available-groups") should be(List("group1", "group3").asJava)
        }
        "at least one block is matched" in {
          val user2MetadataManager = new CurrentUserMetadataManager(container.nodesContainers.head.client("user2", "pass"))

          val result = user2MetadataManager.fetchMetadata()

          assertEquals(200, result.getResponseCode)
          result.getResponseJson.size() should be (5)
          result.getResponseJson.get("x-ror-username") should be("user2")
          result.getResponseJson.get("x-ror-current-group") should be("group2")
          result.getResponseJson.get("x-ror-available-groups") should be(List("group2", "group4").asJava)
          result.getResponseJson.get("x-ror-kibana_index") should be("user2_kibana_index")
          result.getResponseJson.get("x-ror-kibana-hidden-apps") should be("user2_app1,user2_app2")
        }
        "block with no available groups collected is matched" in {
          val user3MetadataManager = new CurrentUserMetadataManager(container.nodesContainers.head.client("user3", "pass"))

          val result = user3MetadataManager.fetchMetadata()

          assertEquals(200, result.getResponseCode)
          result.getResponseJson.size() should be (3)
          result.getResponseJson.get("x-ror-username") should be("user3")
          result.getResponseJson.get("x-ror-kibana_index") should be("user3_kibana_index")
          result.getResponseJson.get("x-ror-kibana-hidden-apps") should be("user3_app1,user3_app2")
        }
      }
      "return forbidden" when {
        "no block is matched" in {
          val user4MetadataManager = new CurrentUserMetadataManager(container.nodesContainers.head.client("user4", "pass"))

          val result = user4MetadataManager.fetchMetadata()

          assertEquals(401, result.getResponseCode)
        }
      }
    }
  }
}