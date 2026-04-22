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
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.settings.es.RorCoreSettingsLoadingStrategy.LoadingRetryStrategySettings.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.settings.es.YamlFileBasedSettingsLoader.LoadingError
import tech.beshu.ror.utils.DurationOps.{NonNegativeFiniteDuration, PositiveFiniteDuration, RefinedDurationOps}
import tech.beshu.ror.utils.yaml.YamlLeafOrPropertyDecoder

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.language.{implicitConversions, postfixOps}
import scala.util.{Failure, Success, Try}

sealed trait RorCoreSettingsLoadingStrategy
object RorCoreSettingsLoadingStrategy extends YamlFileBasedSettingsLoaderSupport {

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
    implicit val loadingRorCoreStrategySettingsDecoder: YamlLeafOrPropertyDecoder[RorCoreSettingsLoadingStrategy] =
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

    def loadingRorCoreStrategySettingsDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[RorCoreSettingsLoadingStrategy] = {
      for {
        forceLoadFromFile <- forceLoadFromFileDecoder(systemContext)
        loadingRorCoreStrategy <- forceLoadFromFile match {
          case None | Some(false) => loadFromIndexWithFileFallbackDecoder(systemContext)
          case Some(true) => YamlLeafOrPropertyDecoder.pure[RorCoreSettingsLoadingStrategy](ForceLoadingFromFileSettings)
        }
      } yield loadingRorCoreStrategy
    }

    private def forceLoadFromFileDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, Boolean] = { str =>
        str.toLowerCase match {
          case "true" => Right(true)
          case "false" => Right(false)
          case _ => Left(s"Cannot convert '$str' to boolean. Expected 'true' or 'false'")
        }
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.forceLoadFromFileKey),
        creator = creator
      )
    }

    private def loadFromIndexWithFileFallbackDecoder(systemContext: SystemContext): YamlLeafOrPropertyDecoder[RorCoreSettingsLoadingStrategy] = {
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
      val creator: String => Either[String, LoadingAttemptsInterval] = { str =>
        Try(Duration(str)) match {
          case Success(v: FiniteDuration) =>
            v.toRefineNonNegative
              .map(v => LoadingAttemptsInterval(v))
              .left.map(_ => s"Duration '$str' must be non-negative")
          case Success(_) | Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m'")
        }
      }
      val legacyCreator: String => Either[String, LoadingAttemptsInterval] = { str =>
        parseLegacyDuration(str) match {
          case Success(v) =>
            v.toRefineNonNegative
              .map(v => LoadingAttemptsInterval(v))
              .left.map(_ => s"Duration '$str' must be non-negative")
          case Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m' or integer seconds")
        }
      }
      YamlLeafOrPropertyDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.attemptsIntervalKey),
          creator = creator
        )
        .orElse {
          YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(legacyConsts.attemptsInterval, legacyCreator)
        }
    }

    private def loadingAttemptsCountDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, LoadingAttemptsCount] = { str =>
        toNonNegativeInt(str) match {
          case Success(value) => Right(LoadingAttemptsCount(value))
          case Failure(exception) => Left(exception.getMessage)
        }
      }
      YamlLeafOrPropertyDecoder
        .createOptionalValueDecoder(
          path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.attemptsCountKey),
          creator = creator
        ).orElse(
          YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(legacyConsts.attemptsCount, creator)
        )
    }

    private def loadingDelayDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, LoadingDelay] = { str =>
        Try(Duration(str)) match {
          case Success(v: FiniteDuration) =>
            v.toRefineNonNegative
              .map(v => LoadingDelay(v))
              .left.map(_ => s"Duration '$str' must be non-negative")
          case Success(_) | Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m'")
        }
      }
      val legacyCreator: String => Either[String, LoadingDelay] = { str =>
        parseLegacyDuration(str) match {
          case Success(v) =>
            v.toRefineNonNegative
              .map(v => LoadingDelay(v))
              .left.map(_ => s"Duration '$str' must be non-negative")
          case Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m' or integer seconds")
        }
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.retryStrategySection, consts.initialDelayKey),
        creator = creator
      ).orElse(
        YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(legacyConsts.loadingDelay, legacyCreator)
      )
    }

    private def coreRefreshSettingsDecoder(systemContext: SystemContext) = {
      implicit val propertiesProvider: PropertiesProvider = systemContext.propertiesProvider
      val creator: String => Either[String, CoreRefreshSettings] = { str =>
        Try(Duration(str)) match {
          case Success(v: FiniteDuration) if v == Duration.Zero =>
            Right(CoreRefreshSettings.Disabled)
          case Success(v: FiniteDuration) =>
            v.toRefinedPositive
              .map(v => CoreRefreshSettings.Enabled(v))
              .left.map(_ => s"Duration '$str' must be positive (greater than zero)")
          case Success(_) | Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m'")
        }
      }
      val legacyCreator: String => Either[String, CoreRefreshSettings] = { str =>
        parseLegacyDuration(str) match {
          case Success(v) if v == Duration.Zero => Right(CoreRefreshSettings.Disabled)
          case Success(v) =>
            v.toRefinedPositive
              .map(v => CoreRefreshSettings.Enabled(v))
              .left.map(_ => s"Duration '$str' must be positive (greater than zero)")
          case Failure(_) =>
            Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m' or integer seconds")
        }
      }
      YamlLeafOrPropertyDecoder.createOptionalValueDecoder(
        path = NonEmptyList.of(consts.rorSection, consts.loadFromIndexSection, consts.pollIntervalSection),
        creator = creator
      ).orElse(
        YamlLeafOrPropertyDecoder.createLegacyPropertyDecoder(legacyConsts.refreshInterval, legacyCreator)
      )
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

    private def toNonNegativeInt(value: String): Try[Int Refined NonNegative] = Try {
      Try(Integer.valueOf(value)) match {
        case Success(int) if int >= 0 => Refined.unsafeApply(int)
        case Success(_) | Failure(_) => throw new IllegalArgumentException(s"Cannot convert '${value.show}' to non-negative integer")
      }
    }
  }

}
