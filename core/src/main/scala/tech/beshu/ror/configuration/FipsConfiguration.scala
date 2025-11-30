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
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode.NonFips
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*

import java.nio.file.Path

final case class FipsConfiguration(fipsMode: FipsMode)

object FipsConfiguration extends RequestIdAwareLogging {

  def load(esEnv: EsEnv)
          (implicit environmentConfig: EnvironmentConfig): Task[Either[MalformedSettings, FipsConfiguration]] = Task {
    val esConfig = esEnv.elasticsearchConfig
    loadFipsConfigFromFile(esConfig)
      .fold(
        error => Left(error),
        {
          case FipsConfiguration(FipsMode.NonFips) =>
            noRequestIdLogger.info(s"Cannot find FIPS configuration in ${esConfig.show} ...")
            fallbackToRorConfig(esEnv.configPath)
          case ssl =>
            Right(ssl)
        }
      )
  }

  private def fallbackToRorConfig(esConfigFolderPath: Path)
                                 (implicit environmentConfig: EnvironmentConfig) = {
    val rorConfig = new FileConfigLoader(esConfigFolderPath).rawConfigFile
    noRequestIdLogger.info(s"... trying: ${rorConfig.show}")
    if (rorConfig.exists) {
      loadFipsConfigFromFile(rorConfig)
    } else {
      Right(FipsConfiguration(FipsMode.NonFips))
    }
  }

  private def loadFipsConfigFromFile(configFile: File)
                                    (implicit environmentConfig: EnvironmentConfig): Either[MalformedSettings, FipsConfiguration] = {
    new YamlFileBasedConfigLoader(configFile).loadConfig[FipsConfiguration](configName = "ROR FIPS Configuration")
  }

  private implicit val fipsModeDecoder: Decoder[FipsMode] = {
    Decoder.decodeString.emap {
      case "NON_FIPS" => Right(FipsMode.NonFips)
      case "SSL_ONLY" => Right(FipsMode.SslOnly)
      case _ => Left("Invalid configuration option for FIPS MODE. Valid values are: NON_FIPS, SSL_ONLY")
    }
  }

  private implicit val fipsConfigurationDecoder: Decoder[FipsConfiguration] = {
    Decoder.instance { c =>
      c.downField("readonlyrest")
        .getOrElse[FipsMode]("fips_mode")(FipsMode.NonFips)
        .map(FipsConfiguration(_))
    }
  }

  sealed trait FipsMode
  object FipsMode {
    case object NonFips extends FipsMode
    case object SslOnly extends FipsMode
  }

  implicit class FipsConfigurationOps(fipsConfiguration: FipsConfiguration) {
    def isSslFipsCompliant: Boolean = {
      fipsConfiguration.fipsMode match {
        case NonFips => false
        case _ => true
      }
    }
  }
}
