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
import tech.beshu.ror.utils.containers.{EsClusterSettings, EsContainerCreator}

trait AdminApiWithDefaultRorIndexSuite extends BaseAdminApiSuite {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/admin_api/readonlyrest.yml"
  override protected val readonlyrestIndexName: String = ".readonlyrest"

  override protected lazy val rorWithIndexConfig = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  override protected lazy val rorWithNoIndexConfig = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR2",
      configHotReloadingEnabled = false,
      xPackSupport = isUsingXpackSupport,
    )
  )
}