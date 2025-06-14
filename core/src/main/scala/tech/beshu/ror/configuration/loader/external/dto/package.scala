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
package tech.beshu.ror.configuration.loader.external

import cats.Show
import tech.beshu.ror.configuration.loader.LoadedRorConfig
import tech.beshu.ror.implicits.*

package object dto {
  implicit val showLoadedConfigError: Show[LoadedRorConfig.Error] = Show.show {
    case LoadedRorConfig.FileNotExist(path) => s"""file not exist: ${path.show}"""
    case LoadedRorConfig.FileParsingError(message) => s"""file parsing error: ${message.show}"""
    case LoadedRorConfig.EsFileNotExist(path) => s"""ES file not exist: ${path.show}"""
    case LoadedRorConfig.EsFileMalformed(path, message) => s"""ES file malformed: ${path.show} ${message.show}"""
    case LoadedRorConfig.CannotUseRorConfigurationWhenXpackSecurityIsEnabled(typeOfConfiguration) =>
      s"""ROR ${typeOfConfiguration.show} cannot be used when XPack Security is enabled"""
    case LoadedRorConfig.IndexParsingError(message) => s"""index parsing error: ${message.show}"""
    case LoadedRorConfig.IndexUnknownStructure => "index unknown structure"
    case LoadedRorConfig.IndexNotExist => "index not exist"
  }
}
