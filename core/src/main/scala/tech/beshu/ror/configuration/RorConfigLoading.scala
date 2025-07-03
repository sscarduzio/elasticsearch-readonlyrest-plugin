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
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.loader.LoadedRorConfig

object RorConfigLoading {
  type ErrorOr[A] = LoadedRorConfig.Error Either A
  type IndexErrorOr[A] = LoadedRorConfig.LoadingIndexError Either A
  type LoadRorConfig[A] = Free[LoadRorConfigAction, A]

  sealed trait LoadRorConfigAction[A]
  object LoadRorConfigAction {
    final case class ForceLoadRorConfigFromFile(settings: LoadFromFileSettings)
      extends LoadRorConfigAction[ErrorOr[LoadedRorConfig[RawRorConfig]]]
    final case class LoadRorConfigFromFile(settings: LoadFromFileSettings)
      extends LoadRorConfigAction[ErrorOr[LoadedRorConfig[RawRorConfig]]]
    final case class LoadRorConfigFromIndex(settings: LoadFromIndexSettings)
      extends LoadRorConfigAction[IndexErrorOr[LoadedRorConfig[RawRorConfig]]]
  }

  def loadRorConfigFromIndex(settings: LoadFromIndexSettings): LoadRorConfig[IndexErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.LoadRorConfigFromIndex(settings))

  def loadRorConfigFromFile(settings: LoadFromFileSettings): LoadRorConfig[ErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.LoadRorConfigFromFile(settings))

  def forceLoadRorConfigFromFile(settings: LoadFromFileSettings): LoadRorConfig[ErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.ForceLoadRorConfigFromFile(settings))

}
