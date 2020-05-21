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

import java.util.concurrent.TimeUnit

import better.files.File
import cats.Show
import cats.implicits._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.refined._
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object RorProperties extends Logging {

  object defaults {
    val refreshInterval: FiniteDuration Refined Positive = refineV(5 second).right.get
    val esHost: String = "localhost"
    val esPort: Int = 9200
    val proxyPort: Int = 5000
  }

  object keys {
    val rorConfig: NonEmptyString = "com.readonlyrest.settings.file.path"
    val refreshInterval: NonEmptyString = "com.readonlyrest.settings.refresh.interval"
    val esHost: NonEmptyString = "com.readonlyrest.proxy.es.host"
    val esPort: NonEmptyString = "com.readonlyrest.proxy.es.port"
    val proxyPort: NonEmptyString = "com.readonlyrest.proxy.port"
  }

  def rorConfigCustomFile(implicit propertiesProvider: PropertiesProvider): Option[File] =
    propertiesProvider
      .getProperty(PropName(keys.rorConfig))
      .map(File(_))

  def rorProxyConfigFile(implicit propertiesProvider: PropertiesProvider): File =
    getProperty(keys.rorConfig, location => Try(File(location)))

  def rorIndexSettingReloadInterval(implicit propertiesProvider: PropertiesProvider): RefreshInterval =
    getProperty(
      keys.refreshInterval,
      str => toRefreshInterval(str),
      RefreshInterval.Enabled(defaults.refreshInterval)
    )

  def rorProxyEsHost(implicit provider: PropertiesProvider): String =
    getProperty(keys.esHost, str => Success(str), defaults.esHost)

  def rorProxyEsPort(implicit provider: PropertiesProvider): Int =
    getProperty(keys.esPort, str => Try(Integer.valueOf(str)), defaults.esPort)

  def rorProxyPort(implicit provider: PropertiesProvider): Int =
    getProperty(keys.proxyPort, str => Try(Integer.valueOf(str)), defaults.proxyPort)

  private def getProperty[T: Show](name: NonEmptyString, fromString: String => Try[T], default: T)
                                  (implicit provider: PropertiesProvider) = {
    getPropertyOf(
      name,
      fromString,
      {
        logger.info(s"No '$name' property found. Using default: ${default.show}")
        default
      }
    )
  }

  private def getProperty[T](name: NonEmptyString, fromString: String => Try[T])
                            (implicit provider: PropertiesProvider) = {
    getPropertyOf(
      name,
      fromString,
      throw new IllegalArgumentException(s"No required '$name' property found.")
    )
  }

  private def getPropertyOf[T](name: NonEmptyString, fromString: String => Try[T], default: => T)
                              (implicit provider: PropertiesProvider): T = {
    provider
      .getProperty(PropName(name))
      .map { stringValue =>
        fromString(stringValue) match {
          case Success(value) => value
          case Failure(ex) => throw new IllegalArgumentException(s"Invalid format of parameter $name=$stringValue", ex)
        }
      }
      .getOrElse {
        default
      }
  }

  private def toRefreshInterval(value: String): Try[RefreshInterval] = Try {
    Try(Integer.valueOf(value)) match {
      case Success(interval) if interval == 0 =>
        RefreshInterval.Disabled
      case Success(interval) if interval > 0 =>
        RefreshInterval.Enabled(refineV[Positive](FiniteDuration(interval.toLong, TimeUnit.SECONDS)).right.get)
      case Failure(_) =>
        throw new IllegalArgumentException(s"Cannot convert '$value' to finite positive duration")
    }
  }

  sealed trait RefreshInterval
  object RefreshInterval {
    case object Disabled extends RefreshInterval
    final case class Enabled(interval: FiniteDuration Refined Positive) extends RefreshInterval

    implicit val show: Show[RefreshInterval] = Show.show {
      case Disabled => "0 sec"
      case Enabled(interval) => interval.value.toString()
    }
  }
}
