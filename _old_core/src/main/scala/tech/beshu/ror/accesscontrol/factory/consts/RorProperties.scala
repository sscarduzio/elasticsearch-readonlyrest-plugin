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
package tech.beshu.ror.accesscontrol.factory.consts

import java.util.concurrent.TimeUnit

import better.files.File
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.providers.PropertiesProvider
import tech.beshu.ror.providers.PropertiesProvider.PropName

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object RorProperties {

  def rorConfigCustomFile(implicit propertiesProvider: PropertiesProvider): Option[File] =
    propertiesProvider
      .getProperty(PropName(NonEmptyString.unsafeFrom("com.readonlyrest.settings.file.path")))
      .map(File(_))

  def rorIndexSettingReloadInterval(implicit propertiesProvider: PropertiesProvider): Option[RefreshInterval] =
    propertiesProvider
      .getProperty(PropName(NonEmptyString.unsafeFrom("com.readonlyrest.settings.refresh.interval")))
      .flatMap { refreshIntervalString => Try(refreshIntervalString.toInt).toOption }
      .flatMap {
        case interval if interval == 0 => Some(RefreshInterval.Disabled)
        case interval if interval > 0 => Some(RefreshInterval.Enabled(FiniteDuration(interval, TimeUnit.SECONDS)))
        case _ => None
      }

  sealed trait RefreshInterval
  object RefreshInterval {
    case object Disabled extends RefreshInterval
    final case class Enabled(interval: FiniteDuration) extends RefreshInterval
  }
}
