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
import cats.Show
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import squants.information.{Information, Megabytes}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName
import tech.beshu.ror.utils.DurationOps.*
import tech.beshu.ror.utils.RefinedUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object RorProperties extends Logging {

  object defaults {
    val refreshInterval: PositiveFiniteDuration = (5 second).toRefinedPositiveUnsafe
    val loadingDelay: PositiveFiniteDuration = (5 second).toRefinedPositiveUnsafe
    val loadingAttemptsCount: Int Refined NonNegative = Refined.unsafeApply(5)
    val loadingAttemptsInterval: PositiveFiniteDuration = (5 second).toRefinedPositiveUnsafe
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

  def rorSettingsCustomFile(implicit propertiesProvider: PropertiesProvider): Option[File] =
    propertiesProvider
      .getProperty(PropName(keys.rorSettingsFilePath))
      .map(File(_))

  def rorIndexSettingsReloadInterval(implicit propertiesProvider: PropertiesProvider): RefreshInterval =
    getProperty(
      keys.rorSettingsRefreshInterval,
      str => toRefreshInterval(str),
      RefreshInterval.Enabled(defaults.refreshInterval)
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
      LoadingDelay(defaults.refreshInterval)
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

  private def toRefreshInterval(value: String): Try[RefreshInterval] = toPositiveFiniteDuration(value).map {
    case Some(value) => RefreshInterval.Enabled(value)
    case None => RefreshInterval.Disabled
  }

  private def toLoadingAttemptsInterval(value: String): Try[LoadingAttemptsInterval] = toPositiveFiniteDuration(value).map {
    case Some(value) => LoadingAttemptsInterval(value)
    case None => LoadingAttemptsInterval(defaults.loadingAttemptsInterval)
  }

  private def toLoadingDelay(value: String): Try[LoadingDelay] = toPositiveFiniteDuration(value).map {
    case Some(value) => LoadingDelay(value)
    case None => LoadingDelay(defaults.loadingDelay)
  }

  private def toLoadingAttempts(value: String): Try[LoadingAttemptsCount] = toNonNegativeInt(value).map(LoadingAttemptsCount.apply)

  private def toPositiveFiniteDuration(value: String): Try[Option[PositiveFiniteDuration]] =
    toPositiveInt(value).map(_.map(_.toLong.seconds.toRefinedPositiveUnsafe))

  private def toPositiveInt(value: String): Try[Option[Int]] = Try {
    Try(Integer.valueOf(value)) match {
      case Success(int) if int == 0 => None
      case Success(int) if int > 0 => Some(int)
      case Success(_) | Failure(_) => throw new IllegalArgumentException(s"Cannot convert '${value.show}' to positive integer")
    }
  }

  private def toNonNegativeInt(value: String): Try[Int Refined NonNegative] = Try {
    Try(Integer.valueOf(value)) match {
      case Success(int) if int > 0 => Refined.unsafeApply(int)
      case Success(_) | Failure(_) => throw new IllegalArgumentException(s"Cannot convert '${value.show}' to non-negative integer")
    }
  }

  final case class LoadingDelay(duration: PositiveFiniteDuration) extends AnyVal
  object LoadingDelay {
    implicit val show: Show[LoadingDelay] = Show[FiniteDuration].contramap(_.duration.value)
  }

  sealed trait RefreshInterval
  object RefreshInterval {
    case object Disabled extends RefreshInterval

    final case class Enabled(interval: PositiveFiniteDuration) extends RefreshInterval

    implicit val show: Show[RefreshInterval] = Show.show {
      case Disabled => "0 sec"
      case Enabled(interval) => interval.value.toString()
    }
  }

  final case class LoadingAttemptsCount(value: Int Refined NonNegative) extends AnyVal
  object LoadingAttemptsCount {
    def unsafeFrom(value: Int): LoadingAttemptsCount = LoadingAttemptsCount(Refined.unsafeApply(value))

    val zero: LoadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(0)

    implicit val show: Show[LoadingAttemptsCount] = Show[Int].contramap(_.value.value)
  }

  final case class LoadingAttemptsInterval(value: PositiveFiniteDuration) extends AnyVal
  object LoadingAttemptsInterval {
    implicit val show: Show[LoadingAttemptsInterval] = Show[FiniteDuration].contramap(_.value.value)
  }
}
