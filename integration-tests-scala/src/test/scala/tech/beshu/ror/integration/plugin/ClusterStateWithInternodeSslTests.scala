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
package tech.beshu.ror.integration.plugin

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

import scala.collection.JavaConverters._
class ClusterStateWithInternodeSslTests
  extends WordSpec
    with ForAllTestContainer
    with BeforeAndAfterAll{

  override lazy val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/cluster_state_internode_ssl/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(
      clusterInitializer = (_, container) => setContainerEnv(container),
      numberOfInstances = 3,
      internodeSslEnabled = true
    )
  )

  private def setContainerEnv(container: ReadonlyRestEsClusterContainer) = {
    container.nodesContainers.map(_.container.setEnv(List("ROR_INTER_KEY_PASS=readonlyrest").asJava))
  }

  private lazy val adminClusterStateManager = new ClusterStateManager(container.nodesContainers.head.adminClient)

  "Health check" should {
    "be successful" when {
      "internode ssl is enabled" in {
        val response = adminClusterStateManager.healthCheck()

        response.responseCode should be(200)
      }
    }
  }
}
