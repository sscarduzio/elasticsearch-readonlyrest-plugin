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
package tech.beshu.ror.settings.ror.source

import scala.annotation.nowarn
import io.circe.{Decoder, Encoder}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.SettingsLoadingError
import tech.beshu.ror.settings.ror.source.ReadWriteSettingsSource.SettingsSavingError

sealed trait SettingsSource[SETTINGS]

@nowarn("msg=unused implicit parameter")
trait ReadOnlySettingsSource[SETTINGS: Decoder, ERROR] extends SettingsSource[SETTINGS] {
  def load()
          (implicit requestId: RequestId): Task[Either[SettingsLoadingError[ERROR], SETTINGS]]
}
object ReadOnlySettingsSource {
  sealed trait SettingsLoadingError[+ERROR]
  object SettingsLoadingError {
    final case class SettingsMalformed(cause: String) extends SettingsLoadingError[Nothing]
    final case class SourceSpecificError[ERROR](error: ERROR) extends SettingsLoadingError[ERROR]
  }
}

@nowarn("msg=unused implicit parameter")
trait ReadWriteSettingsSource[SETTINGS: Encoder : Decoder, READ_SPECIFIC_ERROR, WRITE_SPECIFIC_ERROR]
  extends ReadOnlySettingsSource[SETTINGS, READ_SPECIFIC_ERROR] {

  def save(settings: SETTINGS)
          (implicit requestId: RequestId): Task[Either[SettingsSavingError[WRITE_SPECIFIC_ERROR], Unit]]
}
object ReadWriteSettingsSource {
  sealed trait SettingsSavingError[+ERROR]
  object SettingsSavingError {
    final case class SourceSpecificError[ERROR](error: ERROR) extends SettingsSavingError[ERROR]
  }
}