package tech.beshu.ror.configuration

import java.io.InputStreamReader
import java.nio.file.Path

import better.files.File
import cats.Show
import io.circe.Json
import io.circe.yaml.parser
import monix.eval.Task
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{InvalidContent, SpecializedError}
import tech.beshu.ror.configuration.ConfigLoader.{ConfigLoaderError, RawRorConfig}
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
      content <- parseFileContent(file)
    } yield RawRorConfig(content)
  }

  private def parseFileContent(file: File): Either[ConfigLoaderError[FileConfigError], Json] = {
    file.inputStream.apply { is =>
      parser
        .parse(new InputStreamReader(is))
        .left.map(InvalidContent.apply)
        .flatMap { json => validateRorJson(json) }
    }
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