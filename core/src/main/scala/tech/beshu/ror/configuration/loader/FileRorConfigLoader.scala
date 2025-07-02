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
package tech.beshu.ror.configuration.loader

import better.files.File
import cats.Show
import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.FileRorConfigLoader.Error.FileNotExist
import tech.beshu.ror.configuration.{RawRorConfig, RawRorConfigYamlParser}

class FileRorConfigLoader(rorSettingsFile: File,
                          rarRorConfigYamlParser: RawRorConfigYamlParser)
  extends RorConfigLoader[FileRorConfigLoader.Error] {

  override def load(): Task[Either[Error[FileRorConfigLoader.Error], RawRorConfig]] = {
    val file = rorSettingsFile
    (for {
      _ <- checkIfFileExist(file)
      config <- loadConfigFromFile(file)
    } yield config).value
  }

  private def checkIfFileExist(file: File): EitherT[Task, Error[FileRorConfigLoader.Error], File] =
    EitherT.cond(file.exists, file, SpecializedError(FileNotExist(file)))

  private def loadConfigFromFile(file: File): EitherT[Task, Error[FileRorConfigLoader.Error], RawRorConfig] = {
    EitherT(rarRorConfigYamlParser.fromFile(file).map(_.left.map(ParsingError.apply)))
  }
}

object FileRorConfigLoader {

  sealed trait Error
  object Error {
    final case class FileNotExist(file: File) extends Error

    implicit val show: Show[Error] = Show.show {
      case FileNotExist(file) => s"Cannot find settings file: ${file.pathAsString}"
    }
  }
}