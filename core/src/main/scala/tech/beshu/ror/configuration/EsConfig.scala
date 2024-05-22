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
import cats.data.{EitherT, NonEmptyList}
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.RorSettingsInactiveWhenXpackSecurityIsEnabled.SettingsType
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.{FileNotFound, MalformedContent, RorSettingsInactiveWhenXpackSecurityIsEnabled}
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.yaml.{JsonFile, YamlKeyDecoder}

import scala.language.implicitConversions

final case class EsConfig(rorEsLevelSettings: RorEsLevelSettings,
                          ssl: RorSsl,
                          rorIndex: RorIndexNameConfiguration,
                          fipsConfiguration: FipsConfiguration)

object EsConfig {

  def from(esEnv: EsEnv)
          (implicit environmentConfig: EnvironmentConfig): Task[Either[LoadEsConfigError, EsConfig]] = {
    val configFile = esEnv.elasticsearchConfig
    (for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      esSettings <- parse(configFile, esEnv.isOssDistribution)
      ssl <- loadSslSettings(esEnv, esSettings.xpackSettings)
      rorIndex <- loadRorIndexNameConfiguration(configFile)
      fipsConfiguration <- loadFipsConfiguration(esEnv, esSettings.xpackSettings)
    } yield EsConfig(esSettings.rorSettings, ssl, rorIndex, fipsConfiguration)).value
  }

  private def parse(configFile: File, ossDistribution: Boolean): EitherT[Task, LoadEsConfigError, EsSettings] = {
    implicit val decoder: Decoder[EsSettings] = decoders.esSettingsDecoder(ossDistribution)
    EitherT.fromEither[Task](
      new JsonFile(configFile)
        .parse[EsSettings]
        .left.map(MalformedContent(configFile, _))
    )
  }

  private def loadSslSettings(esEnv: EsEnv, xpackSettings: XpackSettings)
                             (implicit environmentConfig: EnvironmentConfig): EitherT[Task, LoadEsConfigError, RorSsl] = {
    EitherT(RorSsl.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig, error.message))
      .subflatMap { rorSsl =>
        if(rorSsl != RorSsl.noSsl && xpackSettings.securityEnabled) {
          Left(RorSettingsInactiveWhenXpackSecurityIsEnabled(SettingsType.Ssl))
        } else {
          Right(rorSsl)
        }
      }
  }

  private def loadRorIndexNameConfiguration(configFile: File)
                                           (implicit environmentConfig: EnvironmentConfig): EitherT[Task, LoadEsConfigError, RorIndexNameConfiguration] = {
    EitherT(RorIndexNameConfiguration.load(configFile).map(_.left.map(error => MalformedContent(configFile, error.message))))
  }

  private def loadFipsConfiguration(esEnv: EsEnv, xpackSettings: XpackSettings)
                                   (implicit environmentConfig: EnvironmentConfig): EitherT[Task, LoadEsConfigError, FipsConfiguration] = {
    EitherT(FipsConfiguration.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig, error.message))
      .subflatMap { fipsConfiguration =>
        fipsConfiguration.fipsMode match {
          case FipsMode.SslOnly if xpackSettings.securityEnabled =>
            Left(RorSettingsInactiveWhenXpackSecurityIsEnabled(SettingsType.Fips))
          case FipsMode.NonFips | FipsMode.SslOnly =>
            Right(fipsConfiguration)
        }
      }
  }

  final case class EsSettings(rorSettings: RorEsLevelSettings,
                              xpackSettings: XpackSettings)
  final case class RorEsLevelSettings(forceLoadRorFromFile: Boolean)
  final case class XpackSettings(securityEnabled: Boolean)

  sealed trait LoadEsConfigError
  object LoadEsConfigError {
    final case class FileNotFound(file: File) extends LoadEsConfigError
    final case class MalformedContent(file: File, message: String) extends LoadEsConfigError
    final case class RorSettingsInactiveWhenXpackSecurityIsEnabled(settingsType: SettingsType) extends LoadEsConfigError
    object RorSettingsInactiveWhenXpackSecurityIsEnabled {
      sealed trait SettingsType
      object SettingsType {
        case object Ssl extends SettingsType
        case object Fips extends SettingsType
      }
    }
  }

  private object decoders {
    implicit val rorEsLevelSettingsDecoder: Decoder[RorEsLevelSettings] = {
      YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("readonlyrest", "force_load_from_file"),
        default = false
      ) map RorEsLevelSettings.apply
    }

    implicit val xpackSettingsDecoder: Decoder[XpackSettings] = {
      val booleanDecoder = YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("xpack", "security", "enabled"),
        default = true
      )
      val stringDecoder = YamlKeyDecoder[String](
        segments = NonEmptyList.of("xpack", "security", "enabled"),
        default = "true"
      ) map { _.toBoolean }
      (booleanDecoder or stringDecoder) map XpackSettings.apply
    }

    implicit def esSettingsDecoder(isOssDistribution: Boolean): Decoder[EsSettings] = {
      for {
        rorEsLevelSettings <- Decoder[RorEsLevelSettings]
        xpackSettings <-
          if(isOssDistribution) Decoder.const(XpackSettings(securityEnabled = false))
          else Decoder[XpackSettings]
      } yield EsSettings(rorEsLevelSettings, xpackSettings)
    }
  }

}
