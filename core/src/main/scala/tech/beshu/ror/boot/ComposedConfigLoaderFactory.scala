package tech.beshu.ror.boot

import java.nio.file.Path

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.{EsConfig, FileConfigLoader, IndexConfigManager, RorIndexNameConfiguration}
import tech.beshu.ror.es.IndexJsonContentManager
import tech.beshu.ror.providers.EnvVarsProvider

final class ComposedConfigLoaderFactory(esConfigPath: Path, indexContentManager: IndexJsonContentManager)
                                       (implicit envVarsProvider: EnvVarsProvider) {
  import ComposedConfigLoaderFactory._
  private type ErrorOr[A] = EitherT[Task, LoadingError, A]

  def create(): Task[Either[LoadingError, ComposedConfigLoader]] = {
    (for {
      fileConfigLoader <- createFileConfigLoader(esConfigPath)
      indexConfigLoader <- createIndexConfigLoader(indexContentManager, esConfigPath)
      esConfig <- loadEsConfig(esConfigPath)
      composedLoader <- loadConfig(esConfig, fileConfigLoader, indexConfigLoader)
    } yield composedLoader).value
  }

  private def createFileConfigLoader(esConfigPath: Path): ErrorOr[FileConfigLoader] = {
    EitherT.pure(FileConfigLoader.create(esConfigPath))
  }

  private def createIndexConfigLoader(indexContentManager: IndexJsonContentManager, esConfigPath: Path): ErrorOr[IndexConfigManager] = {
    for {
      rorIndexNameConfig <- EitherT(RorIndexNameConfiguration.load(esConfigPath)).leftMap(ms => IndexConfigurationMalformed(ms.message))
      indexConfigManager <- EitherT.pure[Task, LoadingError](new IndexConfigManager(indexContentManager, rorIndexNameConfig))
    } yield indexConfigManager
  }

  private def loadEsConfig(esConfigPath: Path)
                          (implicit envVarsProvider: EnvVarsProvider): ErrorOr[EsConfig] = {
    EitherT {
      EsConfig
        .from(esConfigPath)
        .map(_.left.map {
          case LoadEsConfigError.FileNotFound(file) =>
            FileNotFound(file)
          case LoadEsConfigError.MalformedContent(file, msg) =>
            FileContentMalformed(file, msg)
        })
    }
  }

  private def loadConfig(esConfig: EsConfig, fileConfigLoader: FileConfigLoader, indexConfigManager: IndexConfigManager): ErrorOr[ComposedConfigLoader] = {
    EitherT.pure(new ComposedConfigLoader(fileConfigLoader, indexConfigManager, esConfig.rorEsLevelSettings))
  }


}
object ComposedConfigLoaderFactory {
  sealed trait LoadingError
  final case class FileNotFound(file: better.files.File) extends LoadingError
  final case class FileContentMalformed(file: better.files.File, message: String) extends LoadingError
  final case class IndexConfigurationMalformed(message: String) extends LoadingError
}
