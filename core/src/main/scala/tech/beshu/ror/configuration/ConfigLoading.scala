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
  type Load[A] = Free[LoadA, A]
  sealed trait LoadA[A]
  case class LoadEsConfig(path: Path) extends LoadA[ErrorOr[EsConfig]]
  case class ForceLoadFromFile(path: Path) extends LoadA[ErrorOr[ForcedFileConfig[RawRorConfig]]]
  case class LoadFromFile(path: Path) extends LoadA[ErrorOr[FileConfig[RawRorConfig]]]
  case class LoadFromIndex(index: RorConfigurationIndex) extends LoadA[IndexErrorOr[IndexConfig[RawRorConfig]]]

  def loadFromIndex(index: RorConfigurationIndex): Load[IndexErrorOr[IndexConfig[RawRorConfig]]] =
    Free.liftF(LoadFromIndex(index))

  def loadFromFile(path: Path): Load[ErrorOr[FileConfig[RawRorConfig]]] =
    Free.liftF(LoadFromFile(path))

  def loadEsConfig(path: Path): Load[ErrorOr[EsConfig]] =
    Free.liftF(LoadEsConfig(path))

  def forceLoadFromFile(path: Path): Load[ErrorOr[ForcedFileConfig[RawRorConfig]]] =
    Free.liftF(ForceLoadFromFile(path))

}
