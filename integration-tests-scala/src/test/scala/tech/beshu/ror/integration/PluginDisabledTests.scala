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
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ClusterStateManager, RorApiManager}

class PluginDisabledTests extends WordSpec with ForAllTestContainer {

  override lazy val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/plugin_disabled/readonlyrest.yml"
  )

  "ROR with `enable: false` in settings" should {
    "pass ES request through" in {
      val user1ClusterStateManager = new ClusterStateManager(container.nodesContainers.head.client("user1", "pass"))

      val result = user1ClusterStateManager.catTemplates()

      result.responseCode should be (200)
    }
    "return information that ROR is disabled" when {
      "ROR API endpoint is being called" in {
        val user1MetadataManager = new RorApiManager(container.nodesContainers.head.client("user1", "pass"))

        val result = user1MetadataManager.fetchMetadata()

        result.responseCode should be (503)
        result.responseJson("error")("reason").str should be ("ReadonlyREST plugin was disabled in settings")
      }
    }
  }

}
