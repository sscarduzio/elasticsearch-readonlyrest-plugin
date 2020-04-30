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

import shapeless._

import scala.language.implicitConversions

sealed trait LoadedConfig[A]
object LoadedConfig {
  sealed trait Error
  final case class FileRecoveredConfig[A](value: A, cause: FileRecoveredConfig.Cause) extends LoadedConfig[A]
  object FileRecoveredConfig {
    type Cause = IndexNotExist.type :+: IndexUnknownStructure.type :+: IndexParsingError :+: CNil
    case object IndexNotExist
    case object IndexUnknownStructure
    val indexNotExist: Cause = Coproduct[Cause](IndexNotExist)
    val indexUnknownStructure: Cause = Coproduct[Cause](IndexUnknownStructure)
    def indexParsingError(indexParsingError: IndexParsingError): Cause = Coproduct[Cause](indexParsingError)
  }
  final case class ForcedFileConfig[A](value: A) extends LoadedConfig[A]
  final case class IndexConfig[A](value: A) extends LoadedConfig[A]
  final case class FileParsingError(message: String) extends LoadedConfig.Error
  final case class FileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileNotExist(path: Path) extends LoadedConfig.Error
  final case class EsFileMalformed(path: Path, message: String) extends LoadedConfig.Error
  final case class EsIndexConfigurationMalformed(message: String) extends LoadedConfig.Error
  final case class IndexParsingError(message: String) extends LoadedConfig.Error
}
final case class Path(value: String) extends AnyVal
