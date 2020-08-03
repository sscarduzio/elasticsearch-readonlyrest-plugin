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
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.{LoadedConfig, Path, RorConfigurationIndex}

import scala.language.higherKinds

object ConfigLoading {
  type ErrorOr[A] = LoadedConfig.Error Either A
  type IndexErrorOr[A] = LoadedConfig.LoadingIndexError Either A
  type LoadRorConfig[A] = Free[LoadRorConfigAction, A]
  sealed trait LoadRorConfigAction[A]
  object LoadRorConfigAction {
    final case class LoadEsConfig(path: Path) extends LoadRorConfigAction[ErrorOr[EsConfig]]
    final case class ForceLoadFromFile(path: Path) extends LoadRorConfigAction[ErrorOr[ForcedFileConfig[RawRorConfig]]]
    final case class LoadFromFile(path: Path) extends LoadRorConfigAction[ErrorOr[FileConfig[RawRorConfig]]]
    final case class LoadFromIndex(index: RorConfigurationIndex) extends LoadRorConfigAction[IndexErrorOr[IndexConfig[RawRorConfig]]]
  }

  def loadFromIndex(index: RorConfigurationIndex): LoadRorConfig[IndexErrorOr[IndexConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.LoadFromIndex(index))

  def loadFromFile(path: Path): LoadRorConfig[ErrorOr[FileConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.LoadFromFile(path))

  def loadEsConfig(path: Path): LoadRorConfig[ErrorOr[EsConfig]] =
    Free.liftF(LoadRorConfigAction.LoadEsConfig(path))

  def forceLoadFromFile(path: Path): LoadRorConfig[ErrorOr[ForcedFileConfig[RawRorConfig]]] =
    Free.liftF(LoadRorConfigAction.ForceLoadFromFile(path))

}
