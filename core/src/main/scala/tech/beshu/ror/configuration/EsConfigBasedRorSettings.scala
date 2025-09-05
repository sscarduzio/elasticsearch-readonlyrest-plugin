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
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorSettingsFile, RorSettingsIndex}
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingError.{CannotUseRorSslWhenXPackSecurityIsEnabled, FileNotFound, MalformedContent}
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay, RefreshInterval}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.language.implicitConversions

final case class EsConfigBasedRorSettings(boot: RorBootSettings,
                                          ssl: Option[RorSslSettings],
                                          settingsIndex: RorSettingsIndex,
                                          settingsFile: RorSettingsFile,
                                          settingsMaxSize: Information,
                                          loadingRorCoreStrategy: LoadingRorCoreStrategy)

object EsConfigBasedRorSettings {

  def from(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, EsConfigBasedRorSettings]] = {
    val configFile = esEnv.elasticsearchYmlFile
    val result = for {
      _ <- EitherT.fromEither[Task](Either.cond(configFile.exists, (), FileNotFound(configFile)))
      bootSettings <- loadRorBootSettings(esEnv)
      loadingRorCoreStrategy <- loadLoadingRorCoreStrategy(esEnv)
      settingsFile <- loadRorSettingsFile(esEnv)
      settingsIndex <- loadRorSettingsIndex(esEnv)
      settingsMaxSize <- loadMaxSizeInformation(esEnv)
      xpackSettings <- loadXpackSecuritySettings(esEnv, esEnv.isOssDistribution)
      sslSettings <- loadRorSslSettings(esEnv, settingsFile, xpackSettings)
    } yield EsConfigBasedRorSettings(bootSettings, sslSettings, settingsIndex, settingsFile, settingsMaxSize, loadingRorCoreStrategy)
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
                                 rorSettingsFile: RorSettingsFile,
                                 xpackSecurity: XpackSecurity)
                                (implicit systemContext: SystemContext): EitherT[Task, LoadingError, Option[RorSslSettings]] = {
    EitherT(RorSslSettings.load(rorSettingsFile, esEnv.elasticsearchYmlFile))
      .leftMap(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      .subflatMap {
        case Some(ssl) if xpackSecurity.enabled =>
          Left(CannotUseRorSslWhenXPackSecurityIsEnabled)
        case rorSsl@(Some(_) | None) =>
          Right(rorSsl)
      }
  }

  private def loadRorSettingsFile(esEnv: EsEnv)
                                 (implicit systemContext: SystemContext) = {
    implicit val decoder: Decoder[RorSettingsFile] = decoders.rorSettingsFileDecoder(esEnv)
    load[RorSettingsFile](esEnv, "ROR configuration file name")
  }

  private def loadRorSettingsIndex(esEnv: EsEnv)
                                  (implicit systemContext: SystemContext) = {
    import decoders.rorSettingsIndexDecoder
    load[RorSettingsIndex](esEnv, "ROR configuration index settings")
  }

  private def loadLoadingRorCoreStrategy(esEnv: EsEnv)
                                        (implicit systemContext: SystemContext) = {
    implicit val decoder: Decoder[LoadingRorCoreStrategy] = decoders.loadRorCoreStrategyDecoder(esEnv)
    load[LoadingRorCoreStrategy](esEnv, "ROR loading core settings")
  }

  private def loadMaxSizeInformation(esEnv: EsEnv)
                                    (implicit systemContext: SystemContext) = {
    implicit val decoder: Decoder[Information] = decoders.settingsMaxSizeDecoder()
    load[Information](esEnv, "ROR settings max size")
  }

  private def load[T: Decoder](esEnv: EsEnv, settingsName: String)
                              (implicit systemContext: SystemContext): EitherT[Task, MalformedContent, T] = {
    EitherT.fromEither[Task] {
      val loader = new YamlFileBasedSettingsLoader(esEnv.elasticsearchYmlFile)
      for {
        strategy <- loader
          .loadSettings[T](settingsName)
          .left.map(error => MalformedContent(esEnv.elasticsearchYmlFile, error.message))
      } yield strategy
    }
  }

  sealed trait LoadingRorCoreStrategy
  object LoadingRorCoreStrategy {
    case object ForceLoadingFromFile extends LoadingRorCoreStrategy
    final case class LoadFromIndexWithFileFallback(parameters: LoadFromIndexParameters) // todo: what about retries?
      extends LoadingRorCoreStrategy
  }

  final case class LoadFromIndexParameters(refreshInterval: RefreshInterval,
                                           loadingAttemptsInterval: LoadingAttemptsInterval,
                                           loadingAttemptsCount: LoadingAttemptsCount,
                                           loadingDelay: LoadingDelay) // todo: rename to reload or sth?

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
          Decoder.const(LoadingRorCoreStrategy.ForceLoadingFromFile)
        case false =>
          for {
            loadFromIndexSettings <- loadFromIndexSettingsDecoder(systemContext.propertiesProvider)
          } yield LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(loadFromIndexSettings)
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

    implicit def rorSettingsFileDecoder(esEnv: EsEnv)
                                       (implicit systemContext: SystemContext): Decoder[RorSettingsFile] =
      Decoder.instance(_ => Right(
        RorProperties
          .rorSettingsCustomFile(systemContext.propertiesProvider)
          .getOrElse(RorSettingsFile.default(esEnv))
      ))

    implicit def settingsMaxSizeDecoder()
                                       (implicit systemContext: SystemContext): Decoder[Information] = {
      Decoder.instance(_ => Right(
        RorProperties.rorSettingsMaxSize(systemContext.propertiesProvider)
      ))
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

    private implicit def loadFromIndexSettingsDecoder(propertiesProvider: PropertiesProvider): Decoder[LoadFromIndexParameters] = {
      for {
        refreshInterval <- Decoder.instance(_ => Right(RorProperties.rorIndexSettingsReloadInterval(propertiesProvider)))
        loadingAttemptsInterval <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(propertiesProvider)))
        loadingAttemptsCount <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(propertiesProvider)))
        loadingDelay <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingLoadingDelay(propertiesProvider)))
      } yield LoadFromIndexParameters(
        refreshInterval,
        loadingAttemptsInterval,
        loadingAttemptsCount,
        loadingDelay
      )
    }
  }

}
