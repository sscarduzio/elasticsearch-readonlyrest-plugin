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
import squants.information.Information
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadEsConfigError.{FileNotFound, MalformedContent, RorSettingsInactiveWhenXpackSecurityIsEnabled}
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy.{ForceLoadingFromFile, LoadFromIndexWithFileFallback}
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay, RefreshInterval}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.language.implicitConversions

final case class EsConfigBasedRorSettings(boot: RorBootSettings,
                                          ssl: Option[RorSsl],
                                          rorConfigIndex: RorConfigurationIndex,
                                          loadingRorCoreStrategy: LoadingRorCoreStrategy)

object EsConfigBasedRorSettings {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadEsConfigError, EsConfigBasedRorSettings]] = {
    val configFile = esEnv.elasticsearchYmlFile
    val result = for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      bootSettings <- loadRorBootSettings(esEnv)
      loadingRorCoreStrategyAndIndex <- loadLoadingRorCoreStrategyAndRorIndex(esEnv)
      (loadingRorCoreStrategy, rorIndex) = loadingRorCoreStrategyAndIndex
      xpackSettings <- loadXpackSettings(esEnv, esEnv.isOssDistribution)
      rorFileSettings = loadingRorCoreStrategy match {
        case ForceLoadingFromFile(settings) => settings
        case LoadFromIndexWithFileFallback(_, fallbackSettings) => fallbackSettings
      }
      sslSettings <- loadSslSettings(esEnv, rorFileSettings, xpackSettings)
    } yield EsConfigBasedRorSettings(bootSettings, sslSettings, rorIndex, loadingRorCoreStrategy)
    result.value
  }

  private def loadRorBootSettings(esEnv: EsEnv)
                                 (implicit systemContext: SystemContext) = {
    EitherT(RorBootSettings.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
  }

  private def loadXpackSettings(esEnv: EsEnv, ossDistribution: Boolean)
                               (implicit systemContext: SystemContext) = {
    EitherT {
      Task.delay {
        implicit val xpackSettingsDecoder: Decoder[XpackSettings] = decoders.xpackSettingsDecoder(ossDistribution)
        new YamlFileBasedSettingsLoader(esEnv.elasticsearchYmlFile)
          .loadSettings[XpackSettings](settingsName = "X-Pack settings")
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      }
    }
  }

  private def loadSslSettings(esEnv: EsEnv, rorFileSettings: LoadFromFileSettings, xpackSettings: XpackSettings)
                             (implicit systemContext: SystemContext): EitherT[Task, LoadEsConfigError, Option[RorSsl]] = {
    EitherT(RorSsl.load(esEnv, rorFileSettings))
      .leftMap(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      .subflatMap {
        case Some(ssl) if xpackSettings.securityEnabled =>
          Left(RorSettingsInactiveWhenXpackSecurityIsEnabled)
        case rorSsl@(Some(_) | None) =>
          Right(rorSsl)
      }
  }

  private def loadLoadingRorCoreStrategyAndRorIndex(esEnv: EsEnv)
                                                   (implicit systemContext: SystemContext) = {
    EitherT.fromEither[Task] {
      implicit val loadRorCoreStrategyDecoder: Decoder[LoadingRorCoreStrategy] = decoders.loadRorCoreStrategyDecoder(esEnv)
      import decoders.rorSettingsIndexDecoder
      val loader = new YamlFileBasedSettingsLoader(esEnv.elasticsearchYmlFile)
      for {
        strategy <- loader
          .loadSettings[LoadingRorCoreStrategy](settingsName = "ROR loading core settings")
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
        rorIndex <- loader
          .loadSettings[RorConfigurationIndex](settingsName = "ROR configuration index settings")
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      } yield (strategy, rorIndex)
    }
  }

  sealed trait LoadingRorCoreStrategy
  object LoadingRorCoreStrategy {
    final case class ForceLoadingFromFile(settings: LoadFromFileSettings) extends LoadingRorCoreStrategy
    final case class LoadFromIndexWithFileFallback(settings: LoadFromIndexSettings,
                                                   fallbackSettings: LoadFromFileSettings)
      extends LoadingRorCoreStrategy
  }

  implicit class FromLoadingRorCoreStrategy(val strategy: LoadingRorCoreStrategy) extends AnyVal {
    def rorSettingsFile: File = strategy match {
      case ForceLoadingFromFile(settings) => settings.rorSettingsFile
      case LoadFromIndexWithFileFallback(_, fallbackSettings) => fallbackSettings.rorSettingsFile
    }

    def rorSettingsMaxSize: Information = strategy match {
      case ForceLoadingFromFile(settings) => settings.settingsMaxSize
      case LoadFromIndexWithFileFallback(settings, _) => settings.settingsMaxSize
    }
  }

  final case class LoadFromFileSettings(rorSettingsFile: File,
                                        settingsMaxSize: Information)
  final case class LoadFromIndexSettings(rorConfigIndex: RorConfigurationIndex,
                                         refreshInterval: RefreshInterval,
                                         loadingAttemptsInterval: LoadingAttemptsInterval,
                                         loadingAttemptsCount: LoadingAttemptsCount,
                                         loadingDelay: LoadingDelay,
                                         settingsMaxSize: Information)

  private final case class XpackSettings(securityEnabled: Boolean)

  sealed trait LoadEsConfigError
  object LoadEsConfigError {
    final case class FileNotFound(file: File) extends LoadEsConfigError
    final case class MalformedContent(file: File, message: String) extends LoadEsConfigError
    case object RorSettingsInactiveWhenXpackSecurityIsEnabled extends LoadEsConfigError
  }

  private object decoders {
    implicit def loadRorCoreStrategyDecoder(esEnv: EsEnv)
                                           (implicit systemContext: SystemContext): Decoder[LoadingRorCoreStrategy] = {
      YamlKeyDecoder[Boolean](
        path = NonEmptyList.of("readonlyrest", "force_load_from_file"),
        default = false
      ) flatMap {
        case true =>
          loadFromFileSettingsDecoder(esEnv, systemContext.propertiesProvider)
            .map(LoadingRorCoreStrategy.ForceLoadingFromFile.apply)
        case false =>
          for {
            loadFromIndexSettings <- loadFromIndexSettingsDecoder(systemContext.propertiesProvider)
            loadFromFileSettings <- loadFromFileSettingsDecoder(esEnv, systemContext.propertiesProvider)
          } yield LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(loadFromIndexSettings, loadFromFileSettings)
      }
    }

    implicit val rorSettingsIndexDecoder: Decoder[RorConfigurationIndex] = {
      implicit val indexNameDecoder: Decoder[RorConfigurationIndex] =
        Decoder[NonEmptyString]
          .map(IndexName.Full.apply)
          .map(RorConfigurationIndex.apply)
      YamlKeyDecoder[RorConfigurationIndex](
        path = NonEmptyList.of("readonlyrest", "settings_index"),
        default = RorConfigurationIndex.default
      )
    }

    def xpackSettingsDecoder(isOssDistribution: Boolean): Decoder[XpackSettings] = {
      if (isOssDistribution) {
        Decoder.const(XpackSettings(securityEnabled = false))
      } else {
        val booleanDecoder = YamlKeyDecoder[Boolean](
          path = NonEmptyList.of("xpack", "security", "enabled"),
          default = true
        )
        val stringDecoder = YamlKeyDecoder[String](
          path = NonEmptyList.of("xpack", "security", "enabled"),
          default = "true"
        ) map {
          _.toBoolean
        }
        (booleanDecoder or stringDecoder) map XpackSettings.apply
      }
    }

    private implicit def loadFromFileSettingsDecoder(esEnv: EsEnv,
                                                     propertiesProvider: PropertiesProvider): Decoder[LoadFromFileSettings] = {
      for {
        settingsFile <- Decoder.instance(_ => Right(
          RorProperties.rorSettingsCustomFile( propertiesProvider).getOrElse(esEnv.configDir / "readonlyrest.yml")
        ))
        settingsMaxSize <- Decoder.instance(_ => Right(
          RorProperties.rorSettingsMaxSize(propertiesProvider)
        ))
      } yield LoadFromFileSettings(settingsFile, settingsMaxSize)
    }

    private implicit def loadFromIndexSettingsDecoder(propertiesProvider: PropertiesProvider): Decoder[LoadFromIndexSettings] = {
      for {
        settingsIndex <- rorSettingsIndexDecoder
        refreshInterval <- Decoder.instance(_ => Right(RorProperties.rorIndexSettingsReloadInterval(propertiesProvider)))
        loadingAttemptsInterval <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(propertiesProvider)))
        loadingAttemptsCount <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(propertiesProvider)))
        loadingDelay <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingLoadingDelay(propertiesProvider)))
        settingsMaxSize <- Decoder.instance(_ => Right(RorProperties.rorSettingsMaxSize(propertiesProvider)))
      } yield LoadFromIndexSettings(
        settingsIndex,
        refreshInterval,
        loadingAttemptsInterval,
        loadingAttemptsCount,
        loadingDelay,
        settingsMaxSize
      )
    }
  }

}
