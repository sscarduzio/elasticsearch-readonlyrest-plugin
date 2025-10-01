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

import better.files.File
import cats.data.EitherT
import io.circe.{Decoder, Json}
import monix.eval.Task
import tech.beshu.ror.settings.ror.source.FileSettingsSource.FileSettingsLoadingError
import tech.beshu.ror.settings.ror.source.FileSettingsSource.LoadingError.FileNotExist
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.settings.ror.source.ReadOnlySettingsSource.LoadingSettingsError.SourceSpecificError

class FileSettingsSource[SETTINGS: Decoder](val settingsFile: File)
  extends ReadOnlySettingsSource[SETTINGS, FileSettingsSource.LoadingError] {

  override def load(): Task[Either[FileSettingsLoadingError, SETTINGS]] = {
    (for {
      _ <- checkIfFileExist(settingsFile)
      settings <- loadSettingsFromFile(settingsFile)
    } yield settings).value
  }

  private def checkIfFileExist(file: File): EitherT[Task, FileSettingsLoadingError, File] =
    EitherT.cond(file.exists, file, SourceSpecificError(FileNotExist(file)))

  private def loadSettingsFromFile(file: File): EitherT[Task, FileSettingsLoadingError, SETTINGS] = {
    EitherT
      .pure[Task, FileSettingsLoadingError](file.contentAsString)
      .subflatMap { raw =>
        Json
          .fromString(raw).as[SETTINGS]
          .left.map { failure => LoadingSettingsError.SettingsMalformed(failure.message) }
      }
  }
}
object FileSettingsSource {

  type FileSettingsLoadingError = LoadingSettingsError[LoadingError]

  sealed trait LoadingError
  object LoadingError {
    final case class FileNotExist(file: File) extends LoadingError
  }
}
