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
package tech.beshu.ror.configuration.loader.distributed.dto

import tech.beshu.ror.configuration.loader.LoadedConfig

object LoadedConfigError {
  def createError(error: LoadedConfig.Error): String = error match {
    case LoadedConfig.FileNotExist(path) => s"""file not exist: ${path.value}"""
    case LoadedConfig.FileParsingError(message) => s"""file parsing error: ${message}"""
    case LoadedConfig.EsFileNotExist(path) => s"""es file not exist: ${path.value}"""
    case LoadedConfig.EsFileMalformed(path, message) => s"""es file malformed: ${path} ${message}"""
    case LoadedConfig.IndexParsingError(message) => s"""index parsing error: ${message}"""
    case LoadedConfig.IndexUnknownStructure => "index unknown structure"
  }
}
