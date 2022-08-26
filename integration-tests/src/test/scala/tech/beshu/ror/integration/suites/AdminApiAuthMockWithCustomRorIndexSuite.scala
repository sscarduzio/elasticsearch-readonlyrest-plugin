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
import tech.beshu.ror.integration.suites.base.BaseAdminApiAuthMockSuite
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterProvider, EsClusterSettings, NoOpElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.containers.SecurityType.RorSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait AdminApiAuthMockWithCustomRorIndexSuite extends BaseAdminApiAuthMockSuite {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName: String = "/admin_api_mocks/readonlyrest.yml"
  override protected val readonlyrestIndexName: String = "custom_ror_index"

  override protected lazy val esCluster: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR1",
      numberOfInstances = 2,
      securityType = RorSecurity(Attributes.default.copy(
        customSettingsIndex = Some(readonlyrestIndexName),
        rorConfigFileName = rorConfigFileName
      )),
      nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
      dependentServicesContainers = clusterDependencies
    )
  )

  override def resolvedRorConfig: String = esCluster.resolvedRorConfig(getResourceContent(rorConfigFileName), mode)
}