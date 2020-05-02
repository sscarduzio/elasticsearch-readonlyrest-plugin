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

import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig}
import language.implicitConversions

package object loader {
  implicit def toJava(path: tech.beshu.ror.configuration.loader.Path): java.nio.file.Path = java.nio.file.Paths.get(path.value)

  implicit def toDomain(path: java.nio.file.Path): tech.beshu.ror.configuration.loader.Path = tech.beshu.ror.configuration.loader.Path(path.toString)

  implicit class LoadedConfigOps[A](fa: LoadedConfig[A]) {
    lazy val value: A = fa match {
      case FileRecoveredConfig(value, _) => value
      case ForcedFileConfig(value) => value
      case IndexConfig(value) => value
    }

    def map[B](f: A => B): LoadedConfig[B] = fa match {
      case FileRecoveredConfig(value, cause) => FileRecoveredConfig(f(value), cause)
      case ForcedFileConfig(value) => ForcedFileConfig(f(value))
      case IndexConfig(value) => IndexConfig(f(value))
    }
  }
}
