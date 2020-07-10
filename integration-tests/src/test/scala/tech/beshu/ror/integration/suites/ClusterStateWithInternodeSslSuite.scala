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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ContainerSpecification, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.CatManager

trait ClusterStateWithInternodeSslSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterAll {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_state_internode_ssl/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 3,
      rorContainerSpecification = ContainerSpecification(Map("ROR_INTER_KEY_PASS" -> "readonlyrest")),
      internodeSslEnabled = true
    )
  )

  private lazy val adminClusterStateManager = new CatManager(adminClient, esVersion = targetEs.esVersion)

  "Health check" should {
    "be successful" when {
      "internode ssl is enabled" in {
        val response = adminClusterStateManager.healthCheck()

        response.responseCode should be(200)
      }
    }
  }
}
