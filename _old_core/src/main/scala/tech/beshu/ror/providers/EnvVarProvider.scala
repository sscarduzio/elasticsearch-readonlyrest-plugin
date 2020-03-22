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
package tech.beshu.ror.providers

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName

import scala.util.Try

trait EnvVarsProvider {
  def getEnv(name: EnvVarName): Option[String]
}

object EnvVarProvider {

  final case class EnvVarName(value: NonEmptyString)
}

object OsEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: EnvVarName): Option[String] =
    Try(Option(System.getenv(name.value.value))).toOption.flatten
}
