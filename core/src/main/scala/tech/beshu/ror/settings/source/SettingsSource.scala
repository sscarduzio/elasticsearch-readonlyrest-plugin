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
package tech.beshu.ror.settings.source

import io.circe.{Decoder, Encoder}
import monix.eval.Task
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.source.ReadWriteSettingsSource.SavingSettingsError

sealed trait SettingsSource[SETTINGS]

trait ReadOnlySettingsSource[SETTINGS : Decoder, ERROR] extends SettingsSource[SETTINGS] {
  def load(): Task[Either[LoadingSettingsError[ERROR], SETTINGS]]
}
object ReadOnlySettingsSource {
  sealed trait LoadingSettingsError[+ERROR]
  object LoadingSettingsError {
    case object FormatError extends LoadingSettingsError[Nothing]
    final case class SourceSpecificError[ERROR](error: ERROR) extends LoadingSettingsError[ERROR]
  }

  //implicit val show: Show[LoadingSettingsError[_]] = ???
}

trait ReadWriteSettingsSource[SETTINGS : Encoder : Decoder, READ_SPECIFIC_ERROR, WRITE_SPECIFIC_ERROR]
  extends ReadOnlySettingsSource[SETTINGS, READ_SPECIFIC_ERROR] {

  def save(settings: SETTINGS): Task[Either[SavingSettingsError[WRITE_SPECIFIC_ERROR], Unit]]
}
object ReadWriteSettingsSource {
  sealed trait SavingSettingsError[+ERROR]
  object SavingSettingsError {
    final case class SourceSpecificError[ERROR](error: ERROR) extends SavingSettingsError[ERROR]
  }

  // implicit val show: Show[SavingSettingsError] = ???
}