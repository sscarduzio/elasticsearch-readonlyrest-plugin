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
package tech.beshu.ror.integration.suites

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.generic.{ClientProvider, ClusterProvider, ClusterSettings, SingleContainerCreator, TargetEsContainer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

trait ClusterStateSuite
  extends WordSpec
    with ClientProvider
    with ClusterProvider
    with TargetEsContainer
    with ForAllTestContainer {
  this: SingleContainerCreator =>

  val rorConfigFileName = "/cluster_state/readonlyrest.yml"
  override lazy val container = createLocalClusterContainer(
    ClusterSettings(
      name = "ROR1",
      rorConfigFileName = rorConfigFileName
    )
  )
  override lazy val targetEsContainer = container.nodesContainers.head
  private lazy val adminClusterStateManager = new ClusterStateManager(adminClient)


  "/_cat/state should work as expected" in {
    val response = adminClusterStateManager.healthCheck()

    response.responseCode should be(200)
  }
}
