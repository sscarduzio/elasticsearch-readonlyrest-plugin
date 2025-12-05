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
package tech.beshu.ror.settings

import better.files.File
import cats.Show
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import squants.information.{Information, Megabytes}
import tech.beshu.ror.accesscontrol.domain.RorSettingsFile
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.settings.es.LoadingRorCoreStrategySettings.CoreRefreshSettings
import tech.beshu.ror.settings.es.LoadingRorCoreStrategySettings.LoadingRetryStrategySettings.{LoadingAttemptsCount, LoadingAttemptsInterval, LoadingDelay}
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object RorProperties extends Logging {

  object defaults {
    val refreshInterval: PositiveFiniteDuration = (5 second).toRefinedPositiveUnsafe
    val loadingDelay: NonNegativeFiniteDuration = (5 second).toRefinedNonNegativeUnsafe
    val loadingAttemptsCount: Int Refined NonNegative = Refined.unsafeApply(5)
    val loadingAttemptsInterval: NonNegativeFiniteDuration = (5 second).toRefinedNonNegativeUnsafe
    val rorSettingsMaxSize: Information = Megabytes(3)
  }

  object keys {
    val rorSettingsFilePath: NonEmptyString = nes("com.readonlyrest.settings.file.path")
    val rorSettingsRefreshInterval: NonEmptyString = nes("com.readonlyrest.settings.refresh.interval")
    val startupIndexLoadingDelay: NonEmptyString = nes("com.readonlyrest.settings.loading.delay")
    val startupIndexLoadingAttemptsInterval: NonEmptyString = nes("com.readonlyrest.settings.loading.attempts.interval")
    val startupIndexLoadingAttemptsCount: NonEmptyString = nes("com.readonlyrest.settings.loading.attempts.count")
    val rorSettingsMaxSize: NonEmptyString = nes("com.readonlyrest.settings.maxSize")
  }

  def rorSettingsCustomFile(implicit propertiesProvider: PropertiesProvider): Option[RorSettingsFile] =
    propertiesProvider
      .getProperty(PropName(keys.rorSettingsFilePath))
      .map(f => RorSettingsFile(File(f)))

  def rorCoreRefreshSettings(implicit propertiesProvider: PropertiesProvider): CoreRefreshSettings =
    getProperty(
      keys.rorSettingsRefreshInterval,
      str => toCoreRefreshSettings(str),
      CoreRefreshSettings.Enabled(defaults.refreshInterval)
    )

  def atStartupRorIndexSettingsLoadingAttemptsInterval(implicit propertiesProvider: PropertiesProvider): LoadingAttemptsInterval =
    getProperty(
      keys.startupIndexLoadingAttemptsInterval,
      str => toLoadingAttemptsInterval(str),
      LoadingAttemptsInterval(defaults.loadingAttemptsInterval)
    )

  def atStartupRorIndexSettingsLoadingAttemptsCount(implicit propertiesProvider: PropertiesProvider): LoadingAttemptsCount =
    getProperty(
      keys.startupIndexLoadingAttemptsCount,
      str => toLoadingAttempts(str),
      LoadingAttemptsCount(defaults.loadingAttemptsCount)
    )

  def atStartupRorIndexSettingLoadingDelay(implicit propertiesProvider: PropertiesProvider): LoadingDelay =
    getProperty(
      keys.startupIndexLoadingDelay,
      str => toLoadingDelay(str),
      LoadingDelay(defaults.loadingDelay)
    )

  def rorSettingsMaxSize(implicit propertiesProvider: PropertiesProvider): Information =
    getProperty(
      keys.rorSettingsMaxSize,
      Information.parseString,
      defaults.rorSettingsMaxSize
    )

  private def getProperty[T: Show](name: NonEmptyString, fromString: String => Try[T], default: T)
                                  (implicit provider: PropertiesProvider) = {
    getPropertyOf(
      name,
      fromString,
      {
        logger.info(s"No '${name.show}' property found. Using default: ${default.show}")
        default
      }
    )
  }

  private def getPropertyOf[T](name: NonEmptyString, fromString: String => Try[T], default: => T)
                              (implicit provider: PropertiesProvider): T = {
    provider
      .getProperty(PropName(name))
      .map { stringValue =>
        fromString(stringValue) match {
          case Success(value) => value
          case Failure(ex) => throw new IllegalArgumentException(s"Invalid format of parameter ${name.show}=${stringValue.show}", ex)
        }
      }
      .getOrElse {
        default
      }
  }

  private def toCoreRefreshSettings(value: String): Try[CoreRefreshSettings] = toPositiveFiniteDuration(value).map {
    case Some(value) => CoreRefreshSettings.Enabled(value)
    case None => CoreRefreshSettings.Disabled
  }

  private def toLoadingAttemptsInterval(value: String): Try[LoadingAttemptsInterval] =
    toNonNegativeFiniteDuration(value).map(LoadingAttemptsInterval.apply)

  private def toLoadingDelay(value: String): Try[LoadingDelay] =
    toNonNegativeFiniteDuration(value).map(LoadingDelay.apply)

  private def toLoadingAttempts(value: String): Try[LoadingAttemptsCount] =
    toNonNegativeInt(value).map(LoadingAttemptsCount.apply)

  private def toPositiveFiniteDuration(value: String): Try[Option[PositiveFiniteDuration]] = Try {
    durationFrom(value) match {
      case d if d == Duration.Zero => None
      case d => Some(d.toRefinedPositiveUnsafe)
    }
  }

  private def toNonNegativeFiniteDuration(value: String): Try[NonNegativeFiniteDuration] = Try {
    durationFrom(value).toRefinedNonNegativeUnsafe
  }

  private def durationFrom(value: String) = {
    Try(value.toLong) match {
      case Success(seconds) => FiniteDuration(seconds, TimeUnit.SECONDS)
      case Failure(_) => Duration(value)
    }
  }

  private def toNonNegativeInt(value: String): Try[Int Refined NonNegative] = Try {
    Try(Integer.valueOf(value)) match {
      case Success(int) if int >= 0 => Refined.unsafeApply(int)
      case Success(_) | Failure(_) => throw new IllegalArgumentException(s"Cannot convert '${value.show}' to non-negative integer")
    }
  }
}
