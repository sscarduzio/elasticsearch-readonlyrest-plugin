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
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.loader.FileConfigLoader.FileConfigError.FileNotExist
import tech.beshu.ror.configuration.{RawRorConfig, RorProperties}
import tech.beshu.ror.providers.{JvmPropertiesProvider, PropertiesProvider}
import tech.beshu.ror.utils.PrivilegedFile

class FileConfigLoader(esConfigFile: File,
                       propertiesProvider: PropertiesProvider)
  extends ConfigLoader[FileConfigError] {

  def rawConfigFile: PrivilegedFile = {
    implicit val _ = propertiesProvider
    RorProperties.rorConfigCustomFile match {
      case Some(customRorFile) => customRorFile
      case None => PrivilegedFile(s"${esConfigFile.path.toAbsolutePath}/readonlyrest.yml")
    }
  }

  override def load(): Task[Either[ConfigLoaderError[FileConfigError], RawRorConfig]] = {
    val file = rawConfigFile
    (for {
      _ <- checkIfFileExist(file)
      config <- loadConfigFromFile(file)
    } yield config).value
  }

  private def checkIfFileExist(file: PrivilegedFile): EitherT[Task, ConfigLoaderError[FileConfigError], PrivilegedFile] =
    EitherT.cond(file.exists, file, SpecializedError(FileNotExist(file)))

  private def loadConfigFromFile(file: PrivilegedFile): EitherT[Task, ConfigLoaderError[FileConfigError], RawRorConfig] = {
    EitherT(RawRorConfig.fromFile(file).map(_.left.map(ParsingError.apply)))
  }
}

object FileConfigLoader {

  sealed trait FileConfigError
  object FileConfigError {
    final case class FileNotExist(file: PrivilegedFile) extends FileConfigError

    implicit val show: Show[FileConfigError] = Show.show {
      case FileNotExist(file) => s"Cannot find settings file: ${file.pathAsString}"
    }
  }

  def create(esConfigFolderPath: Path): FileConfigLoader = new FileConfigLoader(File(esConfigFolderPath), JvmPropertiesProvider)
}