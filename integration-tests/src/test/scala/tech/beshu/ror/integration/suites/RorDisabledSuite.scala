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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{CatManager, RorApiManager}

trait RorDisabledSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/plugin_disabled/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      xPackSupport = false,
    )
  )

  "ROR with `enable: false` in settings" should {
    "pass ES request through" in {
      val user1ClusterStateManager = new CatManager(basicAuthClient("user1", "pass"), esVersion = esVersionUsed)

      val result = user1ClusterStateManager.templates()

      result.responseCode should be(200)
    }
    "return information that ROR is disabled" when {
      "ROR API endpoint is being called" in {
        val user1MetadataManager = new RorApiManager(basicAuthClient("user1", "pass"))

        val result = user1MetadataManager.fetchMetadata()

        result.responseCode should be(403)
        result.responseJson("error")("reason").str should be("forbidden")
        result.responseJson("error")("due_to").str should be("READONLYREST_NOT_ENABLED")
      }
    }
  }
}