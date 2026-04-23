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
import eu.timepit.refined.types.all.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.SystemContext
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy.LoadingRetryStrategySettings.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.settings.es.ElasticsearchConfigLoader.LoadingError
import tech.beshu.ror.utils.DurationOps.{NonNegativeFiniteDuration, PositiveFiniteDuration, RefinedDurationOps}
import tech.beshu.ror.utils.FromString
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyOrEnvDecoder

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

sealed trait RorCoreSettingsLoadingStrategy
object RorCoreSettingsLoadingStrategy extends ElasticsearchConfigLoaderSupport {

  case object ForceLoadingFromFileSettings extends RorCoreSettingsLoadingStrategy
  final case class LoadFromIndexWithFileFallback(indexLoadingRetrySettings: LoadingRetryStrategySettings,
                                                 coreRefreshSettings: CoreRefreshSettings)
    extends RorCoreSettingsLoadingStrategy

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
    final case class Enabled(pollInterval: PositiveFiniteDuration) extends CoreRefreshSettings
  }

  def load(esEnv: EsEnv)
          (implicit systemContext: SystemContext): Task[Either[LoadingError, RorCoreSettingsLoadingStrategy]] = {
    implicit val loadingRorCoreStrategySettingsDecoder: YamlLeafOrPropertyOrEnvDecoder[RorCoreSettingsLoadingStrategy] =
      decoders.loadingRorCoreStrategySettingsDecoder(systemContext)
    loadSetting[RorCoreSettingsLoadingStrategy](esEnv, "ROR loading core strategy settings")
  }

  private object decoders {

    object defaults {
      val coreRefreshSettings: CoreRefreshSettings = CoreRefreshSettings.Enabled((5 second).toRefinedPositiveUnsafe)
      val loadingDelay: LoadingDelay = LoadingDelay((5 second).toRefinedNonNegativeUnsafe)
      val loadingAttemptsCount: LoadingAttemptsCount = LoadingAttemptsCount(Refined.unsafeApply(5))
      val loadingAttemptsInterval = LoadingAttemptsInterval((5 second).toRefinedNonNegativeUnsafe)
    }

    private object legacyConsts {
      val refreshInterval: NonEmptyString = NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval")
      val loadingDelay: NonEmptyString = NonEmptyString.unsafeFrom("com.readonlyrest.settings.loading.delay")
      val attemptsInterval: NonEmptyString = NonEmptyString.unsafeFrom("com.readonlyrest.settings.loading.attempts.interval")
      val attemptsCount: NonEmptyString = NonEmptyString.unsafeFrom("com.readonlyrest.settings.loading.attempts.count")
    }

    private object consts {
      val rorSection: NonEmptyString = NonEmptyString.unsafeFrom("readonlyrest")
      val forceLoadFromFileKey: NonEmptyString = NonEmptyString.unsafeFrom("force_load_from_file")
      val loadFromIndexSection: NonEmptyString = NonEmptyString.unsafeFrom("load_from_index")
      val retryStrategySection: NonEmptyString = NonEmptyString.unsafeFrom("initial_loading_retry_strategy")
      val pollIntervalSection: NonEmptyString = NonEmptyString.unsafeFrom("poll_interval")
      val attemptsIntervalKey: NonEmptyString = NonEmptyString.unsafeFrom("attempts_interval")
      val attemptsCountKey: NonEmptyString = NonEmptyString.unsafeFrom("attempts_count")
      val initialDelayKey: NonEmptyString = NonEmptyString.unsafeFrom("initial_delay")
    }

    def loadingRorCoreStrategySettingsDecoder(systemContext: SystemContext): YamlLeafOrPropertyOrEnvDecoder[RorCoreSettingsLoadingStrategy] = {
      for {
        forceLoadFromFile <- forceLoadFromFileDecoder(systemContext)
        loadingRorCoreStrategy <- forceLoadFromFile match {
          case None | Some(false) => loadFromIndexWithFileFallbackDecoder(systemContext)
          case Some(true) => YamlLeafOrPropertyOrEnvDecoder.pure[RorCoreSettingsLoadingStrategy](ForceLoadingFromFileSettings)
        }
      } yield loadingRorCoreStrategy
    }

    private def forceLoadFromFileDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider
      YamlLeafOrPropertyOrEnvDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.forceLoadFromFileKey),
        decoder = FromString.boolean
      )
    }

    private def loadFromIndexWithFileFallbackDecoder(systemContext: SystemContext): YamlLeafOrPropertyOrEnvDecoder[RorCoreSettingsLoadingStrategy] = {
      for {
        loadingRetryStrategySettings <- loadingRetryStrategySettingsDecoder(systemContext)
        coreRefreshSettings <- coreRefreshSettingsDecoder(systemContext)
      } yield LoadFromIndexWithFileFallback(
        loadingRetryStrategySettings,
        coreRefreshSettings.getOrElse(defaults.coreRefreshSettings)
      )
    }

    private def loadingRetryStrategySettingsDecoder(systemContext: SystemContext) = {
      for {
        loadingAttemptsInterval <- loadingAttemptsIntervalDecoder(systemContext)
        loadingAttemptsCount <- loadingAttemptsCountDecoder(systemContext)
        loadingDelay <- loadingDelayDecoder(systemContext)
      } yield LoadingRetryStrategySettings(
        loadingAttemptsInterval.getOrElse(defaults.loadingAttemptsInterval),
        loadingAttemptsCount.getOrElse(defaults.loadingAttemptsCount),
        loadingDelay.getOrElse(defaults.loadingDelay)
      )
    }

    private def loadingAttemptsIntervalDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider
      val decoder: FromString[LoadingAttemptsInterval] =
        FromString.nonNegativeFiniteDuration.map(LoadingAttemptsInterval.apply)
      val legacyDecoder: FromString[LoadingAttemptsInterval] =
        legacyNonNeg(LoadingAttemptsInterval.apply)
      YamlLeafOrPropertyOrEnvDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.attemptsIntervalKey),
          decoder = decoder
        )
        .orElse {
          YamlLeafOrPropertyOrEnvDecoder.createLegacyPropertyDecoder(legacyConsts.attemptsInterval, legacyDecoder)
        }
    }

    private def loadingAttemptsCountDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider
      val decoder: FromString[LoadingAttemptsCount] = FromString.nonNegativeInt.map(LoadingAttemptsCount.apply)
      YamlLeafOrPropertyOrEnvDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.attemptsCountKey),
          decoder = decoder
        ).orElse(
          YamlLeafOrPropertyOrEnvDecoder.createLegacyPropertyDecoder(legacyConsts.attemptsCount, decoder)
        )
    }

    private def loadingDelayDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider
      val decoder: FromString[LoadingDelay] =
        FromString.nonNegativeFiniteDuration.map(LoadingDelay.apply)
      val legacyDecoder: FromString[LoadingDelay] =
        legacyNonNeg(LoadingDelay.apply)
      YamlLeafOrPropertyOrEnvDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.initialDelayKey),
          decoder = decoder
        ).orElse(
          YamlLeafOrPropertyOrEnvDecoder.createLegacyPropertyDecoder(legacyConsts.loadingDelay, legacyDecoder)
        )
    }

    private def coreRefreshSettingsDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      implicit val envVarsProvider: EnvVarsProvider = systemContext.envVarsProvider

      def toRefreshSettings(d: NonNegativeFiniteDuration): CoreRefreshSettings =
        if (d.value == Duration.Zero) CoreRefreshSettings.Disabled
        else CoreRefreshSettings.Enabled(d.value.toRefinedPositiveUnsafe)

      val decoder: FromString[CoreRefreshSettings] =
        FromString.nonNegativeFiniteDuration.map(toRefreshSettings)
      val legacyDecoder: FromString[CoreRefreshSettings] =
        legacyNonNeg(identity).map(toRefreshSettings)
      YamlLeafOrPropertyOrEnvDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.pollIntervalSection),
          decoder = decoder
        ).orElse(
          YamlLeafOrPropertyOrEnvDecoder.createLegacyPropertyDecoder(legacyConsts.refreshInterval, legacyDecoder)
        )
    }

    private def legacyNonNeg[T](ctor: NonNegativeFiniteDuration => T): FromString[T] =
      FromString.instance { str =>
        parseLegacyDuration(str) match {
          case Success(v) =>
            v.toRefineNonNegative.map(ctor).left.map(_ => s"Duration '$str' must be non-negative")
          case Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m' or integer seconds")
        }
      }

    private def parseLegacyDuration(value: String): Try[FiniteDuration] = Try {
      Try(value.toLong) match {
        case Success(seconds) => FiniteDuration(seconds, java.util.concurrent.TimeUnit.SECONDS)
        case Failure(_) => Duration(value) match {
          case d: FiniteDuration => d
          case _ => throw new IllegalArgumentException(s"Expected a finite duration, got '$value'")
        }
      }
    }

  }

}
