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

import better.files.File
import io.circe.{Decoder, DecodingFailure, Json}
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.factory.JsonConfigStaticVariableResolver
import tech.beshu.ror.utils.yaml
import tech.beshu.ror.utils.yaml.YamlOps.jsonWithOneLinerKeysToRegularJson

final class YamlFileBasedConfigLoader(file: File)
                                     (implicit environmentConfig: EnvironmentConfig) {

  private val jsonConfigResolver = new JsonConfigStaticVariableResolver(
    environmentConfig.envVarsProvider,
    TransformationCompiler.withoutAliases(environmentConfig.variablesFunctions)
  )

  def loadConfig[CONFIG: Decoder](configName: String): Either[MalformedSettings, CONFIG] = {
    loadedConfigJson
      .flatMap { json =>
        implicitly[Decoder[CONFIG]]
          .decodeJson(json)
          .left.map(e => MalformedSettings(s"Cannot load $configName from file ${file.pathAsString}. Cause: ${prettyCause(e)}"))
      }
  }

  private lazy val loadedConfigJson: Either[MalformedSettings, Json] = {
    file.fileReader { reader =>
      yaml
        .parser
        .parse(reader)
        .left.map(e => MalformedSettings(s"Cannot parse file ${file.pathAsString} content. Cause: ${e.message}"))
        .flatMap { json =>
          jsonConfigResolver
            .resolve(json)
            .left.map(e => MalformedSettings(s"Unable to resolve environment variables for file ${file.pathAsString}. $e."))
        }
        .map(jsonWithOneLinerKeysToRegularJson)
    }
  }

  private def prettyCause(error: DecodingFailure) = {
    error.message match {
      case message if message.startsWith("DecodingFailure at") => "yaml is malformed"
      case other => other
    }
  }
}

final case class MalformedSettings(message: String)