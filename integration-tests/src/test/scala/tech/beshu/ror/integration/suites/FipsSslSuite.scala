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

import eu.timepit.refined.auto._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.SecurityType.RorWithXpackSecurity
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.{RestSsl => RorRestSsl, InternodeSsl => RorInternodeSsl}
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.{Attributes, InternodeSsl, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.{Enabled, SourceFile}
import tech.beshu.ror.utils.elasticsearch.CatManager
import tech.beshu.ror.utils.misc.EsModule.isCurrentModuleNotExcluded
class FipsSslSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll {

  override implicit val rorConfigFileName = "/fips_ssl/readonlyrest.yml"

  override def clusterContainer: EsClusterContainer = generalClusterContainer

  override def targetEs: EsContainer = generalClusterContainer.nodes.head

  lazy val generalClusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "fips_cluster",
      numberOfInstances = 2,
      securityType = RorWithXpackSecurity(Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
        restSsl = Enabled.Yes(RestSsl.Ror(RorRestSsl.RorFips(SourceFile.RorFile))),
        internodeSsl = Enabled.Yes(InternodeSsl.Ror(RorInternodeSsl.RorFips(SourceFile.RorFile)))
      ))
    )
  )

  private lazy val rorClusterAdminStateManager = new CatManager(clients.last.adminClient, esVersion = esVersionUsed)

  if(isCurrentModuleNotExcluded(allEs6xBelowEs65x)) {
    "Health check" should {
      "be successful" when {
        "internode ssl is enabled" in {
          val response = rorClusterAdminStateManager.healthCheck()

          response.responseCode should be(200)
        }
      }
    }
  }
}
