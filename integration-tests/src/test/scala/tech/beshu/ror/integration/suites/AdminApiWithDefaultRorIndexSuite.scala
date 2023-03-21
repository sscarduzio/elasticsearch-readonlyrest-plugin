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
import tech.beshu.ror.integration.suites.base.BaseAdminApiSuite
import tech.beshu.ror.integration.utils.PluginTestSupport
import tech.beshu.ror.utils.containers.EsClusterSettings
import tech.beshu.ror.utils.containers.SecurityType.RorSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes

class AdminApiWithDefaultRorIndexSuite
  extends BaseAdminApiSuite
    with PluginTestSupport {

  override implicit val rorConfigFileName = "/admin_api/readonlyrest.yml"
  override protected val readonlyrestIndexName: String = ".readonlyrest"

  override protected lazy val rorWithIndexConfig = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR1",
      numberOfInstances = 2,
      securityType = RorSecurity(Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
        rorConfigReloading = Attributes.RorConfigReloading.Enabled(interval = settingsReloadInterval)
      )),
      nodeDataInitializer = nodeDataInitializer()
    )
  )

  override protected lazy val rorWithNoIndexConfig = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR2",
      securityType = RorSecurity(Attributes.default.copy(
        rorConfigFileName = rorConfigFileName
      )),
    )
  )
}