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


import java.nio.file.Path
import better.files.File
import cats.data.EitherT
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.{FileNotFound, MalformedContent}
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.yaml.JsonFile

import java.nio.file.Path

final case class EsConfig(rorEsLevelSettings: RorEsLevelSettings,
                          ssl: RorSsl,
                          rorIndex: RorIndexNameConfiguration,
                          fipsConfiguration: FipsConfiguration)

object EsConfig {

  def from(esConfigFolderPath: Path)
          (implicit envVarsProvider: EnvVarsProvider,
           propertiesProvider: PropertiesProvider): Task[Either[LoadEsConfigError, EsConfig]] = {
    val configFile = File(s"${esConfigFolderPath.toAbsolutePath}/elasticsearch.yml")
    (for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      rorEsLevelSettings <- parse(configFile)
      ssl <- loadSslSettings(esConfigFolderPath, configFile)
      rorIndex <- loadRorIndexNameConfiguration(configFile)
      fipsConfiguration <- loadFipsConfiguration(esConfigFolderPath, configFile)
    } yield EsConfig(rorEsLevelSettings, ssl, rorIndex, fipsConfiguration)).value
  }

  private def parse(configFile: File): EitherT[Task, LoadEsConfigError, RorEsLevelSettings] = {
    import decoders._
    EitherT.fromEither[Task](new JsonFile(configFile).parse[RorEsLevelSettings].left.map(MalformedContent(configFile, _)))
  }

  private def loadSslSettings(esConfigFolderPath: Path, configFile: File)
                             (implicit envVarsProvider:EnvVarsProvider,
                              propertiesProvider: PropertiesProvider): EitherT[Task, LoadEsConfigError, RorSsl] = {
    EitherT(RorSsl.load(esConfigFolderPath).map(_.left.map(error => MalformedContent(configFile, error.message))))
  }

  private def loadRorIndexNameConfiguration(configFile: File): EitherT[Task, LoadEsConfigError, RorIndexNameConfiguration] = {
    EitherT(RorIndexNameConfiguration.load(configFile).map(_.left.map(error => MalformedContent(configFile, error.message))))
  }

  private def loadFipsConfiguration(esConfigFolderPath: Path, configFile: File)
                                   (implicit propertiesProvider: PropertiesProvider): EitherT[Task, LoadEsConfigError, FipsConfiguration] = {
    EitherT(FipsConfiguration.load(esConfigFolderPath).map(_.left.map(error => MalformedContent(configFile, error.message))))
  }

  final case class RorEsLevelSettings(forceLoadRorFromFile: Boolean)

  final case class RorIndexSettings(name: String)

  sealed trait LoadEsConfigError
  object LoadEsConfigError {
    final case class FileNotFound(file: File) extends LoadEsConfigError
    final case class MalformedContent(file: File, message: String) extends LoadEsConfigError
  }

  private object decoders {
    implicit val rorEsLevelSettingsDecoder: Decoder[RorEsLevelSettings] = {
      Decoder.instance { c =>
        val oneLine = c.downField("readonlyrest.force_load_from_file").as[Option[Boolean]]
        val twoLines =  c.downField("readonlyrest").downField("force_load_from_file").as[Option[Boolean]]
        val forceLoadFromFile = (oneLine.toOption.flatten, twoLines.toOption.flatten) match {
          case (Some(result), _) => result
          case (_, Some(result)) => result
          case (_, _) => false
        }
        Right(RorEsLevelSettings(forceLoadFromFile))
      }
    }
  }

}
