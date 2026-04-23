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

  // Returns true if any env var starts with `prefix` and the character immediately
  // after the prefix is not `_`. The `_` exclusion prevents the section prefix
  // "ES_SETTING_FOO_BAR_" from falsely matching "ES_SETTING_FOO_BAR__BAZ_*" where
  // `__BAZ` is an escaped-underscore segment belonging to a sibling section.
  def hasEnvWithPrefix(prefix: String): Boolean = false
}

object EnvVarProvider {

  final case class EnvVarName(value: NonEmptyString)
}

object OsEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: EnvVarName): Option[String] =
    Try(Option(System.getenv(name.value.value))).toOption.flatten

  override def hasEnvWithPrefix(prefix: String): Boolean =
    Try(System.getenv().keySet().stream().anyMatch(k =>
      k.startsWith(prefix) && (k.length == prefix.length || k.charAt(prefix.length) != '_')
    )).getOrElse(false)
}
