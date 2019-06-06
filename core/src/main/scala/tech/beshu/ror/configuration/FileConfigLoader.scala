package tech.beshu.ror.configuration

import java.nio.file.Path

import better.files.File
import cats.Show
import monix.eval.Task
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError.FileNotExist
import tech.beshu.ror.utils.{EnvVarsProvider, OsEnvVarsProvider}

class FileConfigLoader(esConfigFolderPath: Path,
                       envVarsProvider: EnvVarsProvider)
  extends ConfigLoader[FileConfigError] {

  def rawConfigFile: File = {
    envVarsProvider.getEnv(Constants.SETTINGS_YAML_FILE_PATH_PROPERTY) match {
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