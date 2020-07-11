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
package tech.beshu.ror.configuration.loader

import tech.beshu.ror.accesscontrol.domain.IndexName

import scala.language.implicitConversions

sealed trait LoadedConfig[A]
object LoadedConfig {
  sealed trait Error
  sealed trait LoadingIndexError
  final case class FileConfig[A](value: A) extends LoadedConfig[A]
  final case class ForcedFileConfig[A](value: A) extends LoadedConfig[A]
  final case class IndexConfig[A](indexName: RorConfigurationIndex, value: A) extends LoadedConfig[A]
  final case class FileParsingError(message: String) extends LoadedConfig.Error
  final case class FileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileMalformed(path: Path, message: String) extends LoadedConfig.Error
  final case class IndexParsingError(message: String) extends LoadedConfig.Error with LoadingIndexError
  case object IndexUnknownStructure extends LoadedConfig.Error with LoadingIndexError
  case object IndexNotExist  extends LoadingIndexError
}
final case class Path(value: String) extends AnyVal
final case class RorConfigurationIndex(index: IndexName) extends AnyVal
