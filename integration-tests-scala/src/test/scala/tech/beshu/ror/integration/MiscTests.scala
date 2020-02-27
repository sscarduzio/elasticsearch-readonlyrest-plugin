package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.apache.http.message.BasicHeader
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

class MiscTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/misc/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(
      numberOfInstances = 2
    )
  )

  private lazy val userClusterStateManager = new ClusterStateManager(
    container.nodesContainers.head.client("user1", "pass", new BasicHeader("X-Forwarded-For", "es-pub7"))
  )

  "An x_forwarded_for" should {
    "block the request because hostname is not resolvable" in {
      val response = userClusterStateManager.healthCheck()

      response.responseCode should be (401)
    }
  }

}
