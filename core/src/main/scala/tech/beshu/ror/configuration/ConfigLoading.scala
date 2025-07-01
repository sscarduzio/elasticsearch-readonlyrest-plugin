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
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadFromFileSettings
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.DurationOps.NonNegativeFiniteDuration

object ConfigLoading {
  type ErrorOr[A] = LoadedRorConfig.Error Either A
  type IndexErrorOr[A] = LoadedRorConfig.LoadingIndexError Either A
  type LoadRorConfig[A] = Free[LoadConfigAction, A]

  sealed trait LoadConfigAction[A]
  object LoadConfigAction {
    final case class LoadEsConfig(env: EsEnv)
      extends LoadConfigAction[ErrorOr[EsConfig]]
    final case class ForceLoadRorConfigFromFile(settings: LoadFromFileSettings)
      extends LoadConfigAction[ErrorOr[LoadedRorConfig[RawRorConfig]]]
    final case class LoadRorConfigFromFile(settings: LoadFromFileSettings)
      extends LoadConfigAction[ErrorOr[LoadedRorConfig[RawRorConfig]]]
    final case class LoadRorConfigFromIndex(index: RorConfigurationIndex, loadingDelay: NonNegativeFiniteDuration)
      extends LoadConfigAction[IndexErrorOr[LoadedRorConfig[RawRorConfig]]]
  }

  def loadRorConfigFromIndex(index: RorConfigurationIndex,
                             loadingDelay: NonNegativeFiniteDuration): LoadRorConfig[IndexErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadConfigAction.LoadRorConfigFromIndex(index, loadingDelay))

  def loadRorConfigFromFile(settings: LoadFromFileSettings): LoadRorConfig[ErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadConfigAction.LoadRorConfigFromFile(settings))

  def loadEsConfig(env: EsEnv): LoadRorConfig[ErrorOr[EsConfig]] =
    Free.liftF(LoadConfigAction.LoadEsConfig(env))

  def forceLoadRorConfigFromFile(settings: LoadFromFileSettings): LoadRorConfig[ErrorOr[LoadedRorConfig[RawRorConfig]]] =
    Free.liftF(LoadConfigAction.ForceLoadRorConfigFromFile(settings))

}
