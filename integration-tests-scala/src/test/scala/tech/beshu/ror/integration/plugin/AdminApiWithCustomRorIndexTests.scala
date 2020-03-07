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
package tech.beshu.ror.integration.plugin

import tech.beshu.ror.integration.plugin.base.BaseAdminApiTests
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}

class AdminApiWithCustomRorIndexTests extends BaseAdminApiTests {

  override protected val readonlyrestIndexName: String = "custom_ror_index"

  override protected lazy val rorWithIndexConfig: ReadonlyRestEsClusterContainer =
    ReadonlyRestEsCluster.createLocalClusterContainer(
      name = "ROR1",
      rorConfigFileName = "/admin_api/readonlyrest.yml",
      clusterSettings = AdditionalClusterSettings(
        numberOfInstances = 2,
        customRorIndexName = Some(readonlyrestIndexName),
        nodeDataInitializer = BaseAdminApiTests.nodeDataInitializer()
      )
    )

  override protected lazy val rorWithNoIndexConfig: ReadonlyRestEsClusterContainer =
    ReadonlyRestEsCluster.createLocalClusterContainer(
      name = "ROR2",
      rorConfigFileName = "/admin_api/readonlyrest.yml",
      clusterSettings = AdditionalClusterSettings(
        configHotReloadingEnabled = false,
        customRorIndexName = Some(readonlyrestIndexName)
      )
    )
}