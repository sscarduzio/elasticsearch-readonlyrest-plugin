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
package tech.beshu.ror.settings.es

import better.files.File
import io.circe.{Decoder, DecodingFailure, Json}
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.blocks.variables.transformation.TransformationCompiler
import tech.beshu.ror.accesscontrol.factory.JsonStaticVariablesResolver
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError
import tech.beshu.ror.utils.yaml.YamlOps.jsonWithOneLinerKeysToRegularJson
import tech.beshu.ror.utils.yaml.YamlParser

final class YamlFileBasedSettingsLoader(file: File)
                                       (implicit systemContext: SystemContext) {

  private val yamlParser: YamlParser = new YamlParser()

  private val jsonStaticVariableResolver = new JsonStaticVariablesResolver(
    systemContext.envVarsProvider,
    TransformationCompiler.withoutAliases(systemContext.variablesFunctions)
  )

  def loadSettings[SETTINGS: Decoder](settingsName: String): Task[Either[LoadingError, SETTINGS]] = Task.delay {
    for {
      _ <- Either.cond(file.exists, (), LoadingError.FileNotFound(file): LoadingError)
      settings <- loadedSettingsJson
        .flatMap { json =>
          implicitly[Decoder[SETTINGS]]
            .decodeJson(json)
            .left.map(e => createError(s"Cannot load ${settingsName.show} from file ${file.pathAsString.show}. Cause: ${prettyCause(e).show}"))
        }
    } yield settings
  }

  private lazy val loadedSettingsJson: Either[LoadingError, Json] = {
    file.fileReader { reader =>
      yamlParser
        .parse(reader)
        .left.map(e => createError(s"Cannot parse file ${file.pathAsString.show} content. Cause: ${e.message.show}"))
        .flatMap { json =>
          jsonStaticVariableResolver
            .resolve(json)
            .left.map(e => createError(s"Unable to resolve environment variables for file ${file.pathAsString.show}. $e."))
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

  private def createError(message: String) = LoadingError.MalformedSettings(file, message)
}

object YamlFileBasedSettingsLoader {
  sealed trait LoadingError
  object LoadingError {
    final case class FileNotFound(file: File) extends LoadingError
    final case class MalformedSettings(file: File, message: String) extends LoadingError
  }
}

private[es] trait YamlFileBasedSettingsLoaderSupport {

  protected def loadSetting[T: Decoder](esEnv: EsEnv, settingsName: String)
                                       (implicit systemContext: SystemContext): Task[Either[LoadingError, T]] = {
    loadSetting(esEnv.elasticsearchConfig.file, settingsName)
  }

  protected def loadSetting[T: Decoder](file: File, settingsName: String)
                                       (implicit systemContext: SystemContext): Task[Either[LoadingError, T]] = {
    val loader = new YamlFileBasedSettingsLoader(file)
    loader.loadSettings[T](settingsName)
  }

}
