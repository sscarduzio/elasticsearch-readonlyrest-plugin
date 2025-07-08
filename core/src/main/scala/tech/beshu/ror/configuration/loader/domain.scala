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

import java.nio.file.Path

// todo: move?
object LoadedRorConfig {

  sealed trait Error
  final case class FileParsingError(message: String) extends LoadedRorConfig.Error
  final case class FileNotExist(path: Path) extends LoadedRorConfig.Error

  sealed trait LoadingIndexError
  final case class IndexParsingError(message: String) extends LoadedRorConfig.Error with LoadingIndexError
  case object IndexUnknownStructure extends LoadedRorConfig.Error with LoadingIndexError
  case object IndexNotExist extends LoadedRorConfig.Error with LoadingIndexError
}

object LoadedTestRorConfig {

  sealed trait LoadingIndexError
  final case class IndexParsingError(message: String) extends LoadingIndexError
  case object IndexUnknownStructure extends LoadingIndexError
  case object IndexNotExist extends LoadingIndexError
}
