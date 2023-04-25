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
package tech.beshu.ror.configuration

import cats.free.Free
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.configuration.loader.LoadedTestRorConfig

object TestConfigLoading {
  type IndexErrorOr[A] = LoadedTestRorConfig.LoadingIndexError Either A
  type LoadTestRorConfig[A] = Free[LoadTestConfigAction, A]
  sealed trait LoadTestConfigAction[A]
  object LoadTestConfigAction {
    final case class LoadRorConfigFromIndex(index: RorConfigurationIndex)
      extends LoadTestConfigAction[IndexErrorOr[LoadedTestRorConfig.IndexConfig[TestRorConfig]]]
  }

  def loadRorConfigFromIndex(index: RorConfigurationIndex): LoadTestRorConfig[IndexErrorOr[LoadedTestRorConfig.IndexConfig[TestRorConfig]]] =
    Free.liftF(LoadTestConfigAction.LoadRorConfigFromIndex(index))

}
