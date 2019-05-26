package tech.beshu.ror.configuration

import java.io.InputStreamReader
import java.nio.file.Path

import better.files.File
import io.circe.Json
import io.circe.yaml.parser
import monix.eval.Task
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{InvalidContent, SpecializedError}
import tech.beshu.ror.configuration.ConfigLoader.{ConfigLoaderError, RawRorConfig}
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError.FileNotExist
import tech.beshu.ror.utils.EnvVarsProvider

class FileConfigLoader(esConfigFolderPath: Path,
                       envVarsProvider: EnvVarsProvider) extends ConfigLoader[FileConfigError] {

  override def load(): Task[Either[ConfigLoaderError[FileConfigError], RawRorConfig]] = Task {
    val configFile = envVarsProvider.getEnv(Constants.SETTINGS_YAML_FILE_PATH_PROPERTY) match {
      case Some(customRorFilePath) => File(customRorFilePath)
      case None => File(s"${esConfigFolderPath.toAbsolutePath}/readonlyrest.yml")
    }
    loadRorConfigFromFile(configFile)
  }

  private def loadRorConfigFromFile(file: File) = {
    for {
      _ <- Either.cond(file.exists, (), SpecializedError(FileNotExist(file)))
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
  }
}