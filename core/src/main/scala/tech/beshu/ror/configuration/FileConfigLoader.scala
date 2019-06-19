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

import java.nio.file.Path

import better.files.File
import cats.Show
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.domain.EnvVarName
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError.FileNotExist
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider}

class FileConfigLoader(esConfigFolderPath: Path,
                       envVarsProvider: EnvVarsProvider)
  extends ConfigLoader[FileConfigError] {

  def rawConfigFile: File = {
    envVarsProvider.getEnv(EnvVarName(NonEmptyString.unsafeFrom(Constants.SETTINGS_YAML_FILE_PATH_PROPERTY))) match {
      case Some(customRorFilePath) => File(customRorFilePath)
      case None => File(s"${esConfigFolderPath.toAbsolutePath}/readonlyrest.yml")
    }
  }

  override def load(): Task[Either[ConfigLoaderError[FileConfigError], RawRorConfig]] = Task {
    val file = rawConfigFile
    for {
      _ <- Either.cond(file.exists, file, SpecializedError(FileNotExist(file)))
      config <- RawRorConfig.fromFile(file).left.map(ParsingError.apply)
    } yield config
  }
}

object FileConfigLoader {

  sealed trait FileConfigError
  object FileConfigError {
    final case class FileNotExist(file: File) extends FileConfigError

    implicit val show: Show[FileConfigError] = Show.show {
      case FileNotExist(file) => s"Cannot find config file: ${file.pathAsString}"
    }
  }

  def create(esConfigFolderPath: Path): FileConfigLoader = new FileConfigLoader(esConfigFolderPath, OsEnvVarsProvider)
}