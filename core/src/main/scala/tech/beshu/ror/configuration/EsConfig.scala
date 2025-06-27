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
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.RorSettingsInactiveWhenXpackSecurityIsEnabled.SettingsType
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.{FileNotFound, MalformedContent, RorSettingsInactiveWhenXpackSecurityIsEnabled}
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.EsConfig.RorEsLevelSettings.LoadingRorCoreStrategy.{ForceLoadingFromFile, LoadFromIndexWithFileFallback}
import tech.beshu.ror.configuration.FipsConfiguration.FipsMode
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.language.implicitConversions

final case class EsConfig(rorEsLevelSettings: RorEsLevelSettings)

object EsConfig {

  def from(esEnv: EsEnv)
          (implicit environmentConfig: EnvironmentConfig): Task[Either[LoadEsConfigError, EsConfig]] = {
    val configFile = esEnv.elasticsearchConfig
    (for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      rorEsLevelSettings <- loadRorEsLevelSettings(esEnv)
    } yield EsConfig(rorEsLevelSettings)).value
  }

  private def loadRorEsLevelSettings(esEnv: EsEnv)
                                    (implicit environmentConfig: EnvironmentConfig) = {
    for {
      loadingRorCoreStrategyAndIndex <- loadLoadingRorCoreStrategyAndRorIndex(esEnv)
      (loadingRorCoreStrategy, rorIndex) = loadingRorCoreStrategyAndIndex
      xpackSettings <- loadXpackSettings(esEnv, esEnv.isOssDistribution)
      sslSettings <- loadSslSettings(esEnv, xpackSettings)
      fibsConfiguration <- loadFipsConfiguration(esEnv, xpackSettings)
    } yield RorEsLevelSettings(rorIndex, loadingRorCoreStrategy, sslSettings, fibsConfiguration)
  }

  private def loadXpackSettings(esEnv: EsEnv, ossDistribution: Boolean)
                               (implicit environmentConfig: EnvironmentConfig) = {
    EitherT.fromEither[Task] {
      implicit val xpackSettingsDecoder: Decoder[XpackSettings] = decoders.xpackSettingsDecoder(ossDistribution)
      new YamlFileBasedConfigLoader(esEnv.configPath)
        .loadConfig[XpackSettings](configName = "X-Pack settings")
        .left.map(error => MalformedContent(esEnv.configPath, error.message))
    }
  }

  private def loadSslSettings(esEnv: EsEnv, xpackSettings: XpackSettings)
                             (implicit environmentConfig: EnvironmentConfig): EitherT[Task, LoadEsConfigError, RorSsl] = {
    EitherT(RorSsl.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchConfig, error.message))
      .subflatMap { rorSsl =>
        if (rorSsl != RorSsl.noSsl && xpackSettings.securityEnabled) {
          Left(RorSettingsInactiveWhenXpackSecurityIsEnabled(SettingsType.Ssl))
        } else {
          Right(rorSsl)
        }
      }
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

  private def loadLoadingRorCoreStrategyAndRorIndex(esEnv: EsEnv)
                                                   (implicit environmentConfig: EnvironmentConfig) = {
    EitherT.fromEither[Task] {
      import decoders.{loadRorCoreStrategyDecoder, rorConfigurationIndexDecoder}
      val loader = new YamlFileBasedConfigLoader(esEnv.configPath)
      for {
        strategy <- loader
          .loadConfig[LoadingRorCoreStrategy](configName = "ROR loading core settings")
          .left.map(error => MalformedContent(esEnv.configPath, error.message))
        rorIndex <- loader
          .loadConfig[RorConfigurationIndex](configName = "ROR configuration index settings")
          .left.map(error => MalformedContent(esEnv.configPath, error.message))
      } yield (strategy, rorIndex)
    }
  }

  final case class RorEsLevelSettings(rorConfigIndex: RorConfigurationIndex,
                                      loadingRorCoreStrategy: LoadingRorCoreStrategy,
                                      ssl: RorSsl,
                                      fipsConfiguration: FipsConfiguration)
  object RorEsLevelSettings {
    sealed trait LoadingRorCoreStrategy
    object LoadingRorCoreStrategy {
      case object ForceLoadingFromFile extends LoadingRorCoreStrategy
      case object LoadFromIndexWithFileFallback extends LoadingRorCoreStrategy
    }
  }
  private final case class XpackSettings(securityEnabled: Boolean)

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
    implicit val loadRorCoreStrategyDecoder: Decoder[LoadingRorCoreStrategy] = {
      YamlKeyDecoder[Boolean](
        segments = NonEmptyList.of("readonlyrest", "force_load_from_file"),
        default = false
      ) map {
        case true => ForceLoadingFromFile
        case false => LoadFromIndexWithFileFallback
      }
    }

    implicit val rorConfigurationIndexDecoder: Decoder[RorConfigurationIndex] = {
      implicit val indexNameDecoder: Decoder[RorConfigurationIndex] =
        Decoder[NonEmptyString]
          .map(IndexName.Full.apply)
          .map(RorConfigurationIndex.apply)
      YamlKeyDecoder[RorConfigurationIndex](
        segments = NonEmptyList.of("readonlyrest", "settings_index"),
        default = RorConfigurationIndex.default
      )
    }

    def xpackSettingsDecoder(isOssDistribution: Boolean): Decoder[XpackSettings] = {
      if (isOssDistribution) {
        Decoder.const(XpackSettings(securityEnabled = false))
      } else {
        val booleanDecoder = YamlKeyDecoder[Boolean](
          segments = NonEmptyList.of("xpack", "security", "enabled"),
          default = true
        )
        val stringDecoder = YamlKeyDecoder[String](
          segments = NonEmptyList.of("xpack", "security", "enabled"),
          default = "true"
        ) map {
          _.toBoolean
        }
        (booleanDecoder or stringDecoder) map XpackSettings.apply
      }
    }
  }

}
