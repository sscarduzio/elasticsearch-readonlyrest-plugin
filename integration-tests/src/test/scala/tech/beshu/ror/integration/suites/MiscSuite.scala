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
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.ClusterManager

trait MiscSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/misc/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2
    )
  )

  private lazy val userClusterStateManager = new ClusterManager(
    client = basicAuthClient("user1", "pass"),
    additionalHeaders = Map("X-Forwarded-For" -> "es-pub7"),
    esVersion = targetEs.esVersion)

  "An x_forwarded_for" should {
    "block the request because hostname is not resolvable" in {
      val response = userClusterStateManager.healthCheck()

      response.responseCode should be(401)
    }
  }
}