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
package tech.beshu.ror.utils

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider

class TestsEnvVarsProvider(envMap: Map[EnvVarName, String]) extends EnvVarsProvider {
  override def getEnv(name: EnvVarName): Option[String] = envMap.get(name)

  override def hasEnvWithPrefix(prefix: String): Boolean =
    envMap.keys.exists { k =>
      val s = k.value.value
      s.startsWith(prefix) && (s.length == prefix.length || s.charAt(prefix.length) != '_')
    }
}

object TestsEnvVarsProvider {
  def default: TestsEnvVarsProvider = new TestsEnvVarsProvider(Map.empty)

  def usingMap(map: Map[String, String]): TestsEnvVarsProvider = new TestsEnvVarsProvider(
    map.map { case (key, value) => (EnvVarName(NonEmptyString.unsafeFrom(key)), value) }
  )
}
