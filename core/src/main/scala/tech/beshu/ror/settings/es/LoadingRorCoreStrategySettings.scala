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

import cats.data.NonEmptyList
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import io.circe.Decoder
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.settings.es.LoadingRorCoreStrategySettings.LoadingRetryStrategySettings.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError
import tech.beshu.ror.utils.DurationOps.{NonNegativeFiniteDuration, PositiveFiniteDuration, RefinedDurationOps}
import tech.beshu.ror.utils.yaml.YamlKeyDecoder

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}

sealed trait LoadingRorCoreStrategySettings
object LoadingRorCoreStrategySettings extends YamlFileBasedSettingsLoaderSupport {

  case object ForceLoadingFromFile$Settings extends LoadingRorCoreStrategySettings
  final case class LoadFromIndexWithFileFallback(indexLoadingRetrySettings: LoadingRetryStrategySettings,
                                                 coreRefreshSettings: CoreRefreshSettings)
    extends LoadingRorCoreStrategySettings

  final case class LoadingRetryStrategySettings(attemptsInterval: LoadingAttemptsInterval,
                                                attemptsCount: LoadingAttemptsCount,
                                                delay: LoadingDelay)
  object LoadingRetryStrategySettings {

    final case class LoadingAttemptsCount(value: Int Refined NonNegative) extends AnyVal
    object LoadingAttemptsCount {
      def unsafeFrom(value: Int): LoadingAttemptsCount = LoadingAttemptsCount(Refined.unsafeApply(value))

      val zero: LoadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(0)
    }

    final case class LoadingAttemptsInterval(value: NonNegativeFiniteDuration) extends AnyVal
    object LoadingAttemptsInterval {
      def unsafeFrom(value: FiniteDuration): LoadingAttemptsInterval = LoadingAttemptsInterval(value.toRefinedNonNegativeUnsafe)
    }

    final case class LoadingDelay(value: NonNegativeFiniteDuration) extends AnyVal
    object LoadingDelay {
      val none: LoadingDelay = unsafeFrom(0 seconds)

      def unsafeFrom(value: FiniteDuration): LoadingDelay = LoadingDelay(value.toRefinedNonNegativeUnsafe)
    }
  }

  sealed trait CoreRefreshSettings
  object CoreRefreshSettings {
    case object Disabled extends CoreRefreshSettings
    final case class Enabled(refreshInterval: PositiveFiniteDuration) extends CoreRefreshSettings
  }

  def load(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, LoadingRorCoreStrategySettings]] = {
    implicit val decoder: Decoder[LoadingRorCoreStrategySettings] = decoders.loadRorCoreStrategyDecoder(esEnv)
    loadSetting[LoadingRorCoreStrategySettings](esEnv, "ROR loading core strategy settings")
  }

  private object decoders {
    implicit def loadRorCoreStrategyDecoder(esEnv: EsEnv)
                                           (implicit systemContext: SystemContext): Decoder[LoadingRorCoreStrategySettings] = {
      YamlKeyDecoder[Boolean](
        path = NonEmptyList.of("readonlyrest", "force_load_from_file"),
        default = false
      ) flatMap {
        case true =>
          Decoder.const(LoadingRorCoreStrategySettings.ForceLoadingFromFile$Settings)
        case false =>
          for {
            loadingRetryStrategySettings <- loadLoadingRetryStrategySettings(systemContext.propertiesProvider)
            coreRefreshIntervalSettings <- loadCoreRefreshSettings(systemContext.propertiesProvider)
          } yield LoadingRorCoreStrategySettings.LoadFromIndexWithFileFallback(
            loadingRetryStrategySettings, coreRefreshIntervalSettings
          )
      }
    }

    private def loadCoreRefreshSettings(propertiesProvider: PropertiesProvider): Decoder[CoreRefreshSettings] = {
      Decoder.instance(_ => Right(RorProperties.rorCoreRefreshSettings(propertiesProvider)))
    }

    private def loadLoadingRetryStrategySettings(propertiesProvider: PropertiesProvider): Decoder[LoadingRetryStrategySettings] = {
      for {
        loadingAttemptsInterval <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsInterval(propertiesProvider)))
        loadingAttemptsCount <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingsLoadingAttemptsCount(propertiesProvider)))
        loadingDelay <- Decoder.instance(_ => Right(RorProperties.atStartupRorIndexSettingLoadingDelay(propertiesProvider)))
      } yield LoadingRetryStrategySettings(
        loadingAttemptsInterval,
        loadingAttemptsCount,
        loadingDelay
      )
    }
  }

}
