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
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingError.{FileNotFound, MalformedContent, CannotUseRorSslWhenXPackSecurityIsEnabled}
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy.{ForceLoadingFromFile, LoadFromIndexWithFileFallback}
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay, RefreshInterval}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.language.implicitConversions

final case class EsConfigBasedRorSettings(boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          rorSettingsIndex: RorSettingsIndex,
                                          loadingRorCoreStrategy: LoadingRorCoreStrategy)

object EsConfigBasedRorSettings {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, EsConfigBasedRorSettings]] = {
    val configFile = esEnv.elasticsearchYmlFile
    val result = for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      bootSettings <- loadRorBootSettings(esEnv)
      loadingRorCoreStrategyAndIndex <- loadLoadingRorCoreStrategyAndRorIndex(esEnv)
      (loadingRorCoreStrategy, rorIndex) = loadingRorCoreStrategyAndIndex
      xpackSettings <- loadXpackSecuritySettings(esEnv, esEnv.isOssDistribution)
      rorFileSettings = loadingRorCoreStrategy match {
        case ForceLoadingFromFile(settings) => settings
        case LoadFromIndexWithFileFallback(_, fallbackSettings) => fallbackSettings
      }
      sslSettings <- loadRorSslSettings(esEnv, rorFileSettings, xpackSettings)
    } yield EsConfigBasedRorSettings(bootSettings, sslSettings, rorIndex, loadingRorCoreStrategy)
    result.value
  }

  private def loadRorBootSettings(esEnv: EsEnv)
                                 (implicit systemContext: SystemContext) = {
    EitherT(RorBootSettings.load(esEnv))
      .leftMap(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
  }

  private def loadXpackSecuritySettings(esEnv: EsEnv, ossDistribution: Boolean)
                                       (implicit systemContext: SystemContext) = {
    EitherT {
      Task.delay {
        implicit val xpackSettingsDecoder: Decoder[XpackSecurity] = decoders.xpackSettingsDecoder(ossDistribution)
        new YamlFileBasedSettingsLoader(esEnv.elasticsearchYmlFile)
          .loadSettings[XpackSecurity](settingsName = "X-Pack settings")
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      }
    }
  }

  private def loadRorSslSettings(esEnv: EsEnv,
                                 rorSettingsFromFileParameters: LoadFromFileParameters,
                                 xpackSecurity: XpackSecurity)
                                (implicit systemContext: SystemContext): EitherT[Task, LoadingError, Option[RorSslSettings]] = {
    EitherT(RorSslSettings.load(rorSettingsFromFileParameters.rorSettingsFile, esEnv.elasticsearchYmlFile))
      .leftMap(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      .subflatMap {
        case Some(ssl) if xpackSecurity.enabled =>
          Left(CannotUseRorSslWhenXPackSecurityIsEnabled)
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
          .loadSettings[RorSettingsIndex](settingsName = "ROR configuration index settings")
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      } yield (strategy, rorIndex)
    }
  }

  sealed trait LoadingRorCoreStrategy
  object LoadingRorCoreStrategy {
    final case class ForceLoadingFromFile(parameters: LoadFromFileParameters) extends LoadingRorCoreStrategy
    final case class LoadFromIndexWithFileFallback(parameters: LoadFromIndexParameters,
                                                   fallbackParameters: LoadFromFileParameters)
      extends LoadingRorCoreStrategy
  }

  implicit class ExtractFromLoadingRorSettingsStrategy(val strategy: LoadingRorCoreStrategy) extends AnyVal {
    def rorSettingsFile: File = strategy match {
      case ForceLoadingFromFile(parameters) => parameters.rorSettingsFile
      case LoadFromIndexWithFileFallback(_, fallbackParameters) => fallbackParameters.rorSettingsFile
    }

    def rorSettingsMaxSize: Information = strategy match {
      case ForceLoadingFromFile(parameters) => parameters.settingsMaxSize
      case LoadFromIndexWithFileFallback(parameters, _) => parameters.settingsMaxSize
    }
  }

  final case class LoadFromFileParameters(rorSettingsFile: File,
                                          settingsMaxSize: Information)
  final case class LoadFromIndexParameters(rorSettingsIndex: RorSettingsIndex,
                                           refreshInterval: RefreshInterval,
                                           loadingAttemptsInterval: LoadingAttemptsInterval,
                                           loadingAttemptsCount: LoadingAttemptsCount,
                                           loadingDelay: LoadingDelay,
                                           settingsMaxSize: Information)

  private final case class XpackSecurity(enabled: Boolean)

  sealed trait LoadingError
  object LoadingError {
    final case class FileNotFound(file: File) extends LoadingError
    final case class MalformedContent(file: File, message: String) extends LoadingError
    case object CannotUseRorSslWhenXPackSecurityIsEnabled extends LoadingError
  }

  private object decoders {
    implicit def loadRorCoreStrategyDecoder(esEnv: EsEnv)
                                           (implicit systemContext: SystemContext): Decoder[LoadingRorCoreStrategy] = {
      YamlKeyDecoder[Boolean](
        path = NonEmptyList.of("readonlyrest", "force_load_from_file"),
        default = false
      ) flatMap {
        case true =>
          loadFromFileParametersDecoder(esEnv, systemContext.propertiesProvider)
            .map(LoadingRorCoreStrategy.ForceLoadingFromFile.apply)
        case false =>
          for {
            loadFromIndexSettings <- loadFromIndexSettingsDecoder(systemContext.propertiesProvider)
            loadFromFileSettings <- loadFromFileParametersDecoder(esEnv, systemContext.propertiesProvider)
          } yield LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(loadFromIndexSettings, loadFromFileSettings)
      }
    }

    implicit val rorSettingsIndexDecoder: Decoder[RorSettingsIndex] = {
      implicit val indexNameDecoder: Decoder[RorSettingsIndex] =
        Decoder[NonEmptyString]
          .map(IndexName.Full.apply)
          .map(RorSettingsIndex.apply)
      YamlKeyDecoder[RorSettingsIndex](
        path = NonEmptyList.of("readonlyrest", "settings_index"),
        default = RorSettingsIndex.default
      )
    }

    def xpackSettingsDecoder(isOssDistribution: Boolean): Decoder[XpackSecurity] = {
      if (isOssDistribution) {
        Decoder.const(XpackSecurity(enabled = false))
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
        (booleanDecoder or stringDecoder) map XpackSecurity.apply
      }
    }

    private implicit def loadFromFileParametersDecoder(esEnv: EsEnv,
                                                       propertiesProvider: PropertiesProvider): Decoder[LoadFromFileParameters] = {
      for {
        settingsFile <- Decoder.instance(_ => Right(
          RorProperties.rorSettingsCustomFile(propertiesProvider).getOrElse(esEnv.configDir / "readonlyrest.yml")
        ))
        settingsMaxSize <- Decoder.instance(_ => Right(
          RorProperties.rorSettingsMaxSize(propertiesProvider)
        ))
      } yield LoadFromFileParameters(settingsFile, settingsMaxSize)
    }

    private implicit def loadFromIndexSettingsDecoder(propertiesProvider: PropertiesProvider): Decoder[LoadFromIndexParameters] = {
      for {
        settingsIndex <- rorSettingsIndexDecoder
        refreshInterval <- Decoder.instance(_ => Right(RorProperties.rorIndexSettingsReloadInterval(propertiesProvider)))
        loadingAttemptsInterval <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(propertiesProvider)))
        loadingAttemptsCount <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(propertiesProvider)))
        loadingDelay <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingLoadingDelay(propertiesProvider)))
        settingsMaxSize <- Decoder.instance(_ => Right(RorProperties.rorSettingsMaxSize(propertiesProvider)))
      } yield LoadFromIndexParameters(
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
