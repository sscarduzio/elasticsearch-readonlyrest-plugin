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

import better.files.File
import cats.data.EitherT
import io.circe.{Decoder, DecodingFailure, ParsingFailure, parser}
import monix.eval.Task
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError

class FileSettingsSource[SETTINGS: Decoder](rorSettingsFile: File)
  extends ReadOnlySettingsSource[SETTINGS] {

  override def load(): Task[Either[LoadingSettingsError, SETTINGS]] = {
    (for {
      _ <- checkIfFileExist(rorSettingsFile)
      settings <- loadSettingsFromFile(rorSettingsFile)
    } yield settings).value
  }

  private def checkIfFileExist(file: File): EitherT[Task, LoadingSettingsError, File] =
    ???
    // todo: EitherT.cond(file.exists, file, SourceSpecificError(FileNotExist(file)))

  private def loadSettingsFromFile(file: File): EitherT[Task, LoadingSettingsError, SETTINGS] = {
    EitherT
      .pure[Task, LoadingSettingsError](file.contentAsString)
      .subflatMap { raw =>
        parser
          .decode(raw)
          .left.map {
            case ParsingFailure(_, _) => LoadingSettingsError.FormatError
            case _: DecodingFailure => LoadingSettingsError.FormatError
          }
      }
  }
}
object FileSettingsSource {
  sealed trait LoadingError
  object LoadingError {
    final case class FileNotExist(file: File) extends LoadingError
  }
}
