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

import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ContainerSpecification, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager

trait ClusterStateWithInternodeSslSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterAll {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_state_internode_ssl/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 3,
      rorContainerSpecification = ContainerSpecification(Map("ROR_INTER_KEY_PASS" -> "readonlyrest")),
      internodeSslEnabled = true
    )
  )

  private lazy val adminClusterStateManager = new ClusterStateManager(adminClient)

  "Health check" should {
    "be successful" when {
      "internode ssl is enabled" in {
        val response = adminClusterStateManager.healthCheck()

        response.responseCode should be(200)
      }
    }
  }
}
