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

import tech.beshu.ror.integration.suites.base.BaseAdminApiSuite
import tech.beshu.ror.integration.utils.PluginTestSupport
import tech.beshu.ror.utils.containers.EsClusterSettings.positiveInt
import tech.beshu.ror.utils.containers.SecurityType.{RorSecurity, RorWithXpackSecurity}
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterSettings, SecurityType}

class AdminApiWithDefaultRorIndexSuite
  extends BaseAdminApiSuite
    with PluginTestSupport {

  override implicit val rorConfigFileName: String = "/admin_api/readonlyrest.yml"
  override protected val readonlyrestIndexName: String = ".readonlyrest"

  override protected lazy val rorWithIndexConfig: EsClusterContainer = {
    def esClusterSettingsCreator(securityType: SecurityType) =
      EsClusterSettings.create(
        clusterName = "ROR1",
        numberOfInstances = positiveInt(2),
        securityType = securityType,
        nodeDataInitializer = nodeDataInitializer()
      )

    createLocalClusterContainer(
      esNewerOrEqual63ClusterSettings = esClusterSettingsCreator(
        RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName,
          rorConfigReloading = Enabled.Yes(settingsReloadInterval)
        ))
      ),
      esOlderThan63ClusterSettings = esClusterSettingsCreator(
        RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName,
          rorConfigReloading = Enabled.Yes(settingsReloadInterval)
        ))
      )
    )
  }

  override protected lazy val rorWithNoIndexConfig: EsClusterContainer = {
    def esClusterSettingsCreator(securityType: SecurityType) =
      EsClusterSettings.create(
        clusterName = "ROR2",
        securityType = securityType,
      )

    createLocalClusterContainer(
      esNewerOrEqual63ClusterSettings = esClusterSettingsCreator(
        RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName
        ))
      ),
      esOlderThan63ClusterSettings = esClusterSettingsCreator(
        RorSecurity(ReadonlyRestPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName
        ))
      )
    )
  }
}