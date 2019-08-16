package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

class ClusterStateTests  extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/cluster_state/readonlyrest.yml",
    numberOfInstances = 1
  )

  private lazy val adminClusterStateManager = new ClusterStateManager(container.nodesContainers.head.adminClient)

  "/_cat/state should work as expected" in {
    val response = adminClusterStateManager.healthCheck()

    response.getResponseCode should be (200)
  }
}
