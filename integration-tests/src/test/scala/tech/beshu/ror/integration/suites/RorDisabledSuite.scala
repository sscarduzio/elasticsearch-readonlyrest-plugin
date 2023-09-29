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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.SecurityType.{RorSecurity, RorWithXpackSecurity}
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterSettings}
import tech.beshu.ror.utils.elasticsearch.{CatManager, RorApiManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class RorDisabledSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/plugin_disabled/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    esNewerOrEqual63ClusterSettings = EsClusterSettings.create(
      clusterName = "ROR1",
      securityType = RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
      )),
    ),
    esOlderThan63ClusterSettings =  EsClusterSettings.create(
      clusterName = "ROR1",
      securityType = RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
      )),
    )
  )

  "ROR with `enable: false` in settings" should {
    "pass ES request through" in {
      val user1ClusterStateManager = new CatManager(basicAuthClient("user1", "pass"), esVersion = esVersionUsed)

      val result = user1ClusterStateManager.templates()

      result should have statusCode 200
    }
    "return information that ROR is disabled" when {
      "ROR API endpoint is being called" in {
        val user1MetadataManager = new RorApiManager(basicAuthClient("user1", "pass"), esVersionUsed)

        val result = user1MetadataManager.fetchMetadata()

        result should have statusCode 403
        result.responseJson("error")("reason").str should be("forbidden")
        result.responseJson("error")("due_to").str should be("READONLYREST_NOT_ENABLED")
      }
    }
  }
}