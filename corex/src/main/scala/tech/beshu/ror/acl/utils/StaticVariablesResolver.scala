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
package tech.beshu.ror.acl.utils

import scala.util.Try

class StaticVariablesResolver(envVarsProvider: EnvVarsProvider) {

  def resolve(value: String): Option[String] = {
    tryToResolveOldStyle(value).orElse(tryToResolve(value))
  }

  private def tryToResolveOldStyle(value: String) = {
    if (value.startsWith(Variables.env)) findVariable("env", value.substring(Variables.env.length))
    else if (value.startsWith(Variables.text)) Some(value.substring(Variables.text.length))
    else None
  }

  private def tryToResolve(withVariable: String) = {
    Variables.regex
      .findAllMatchIn(withVariable)
      .foldLeft(Option(withVariable)) {
        case (Some(currentValue), regexMatch) =>
          if (regexMatch.groupCount != 3) Some(currentValue)
          else {
            val variableType = regexMatch.group(2)
            val variableName = regexMatch.group(3)
            findVariable(variableType, variableName)
              .map { value =>
                val variableGroup = regexMatch.group(1)
                currentValue.replace(variableGroup, value)
              }
          }
        case (None, _) => None
      }
  }

  private def findVariable(`type`: String, name: String): Option[String] =
    `type` match {
      case "env" => envVarsProvider.getEnv(name)
      case _ => None
    }

  private object Variables {
    val regex = ".*(@\\{(.*):(.*)\\}).*".r

    val env = "env:"
    val text = "text:"
  }

}

trait EnvVarsProvider {
  def getEnv(name: String): Option[String]
}

object JavaEnvVarsProvider extends EnvVarsProvider {
  override def getEnv(name: String): Option[String] =
    Try(Option(System.getenv(name))).toOption.flatten
}