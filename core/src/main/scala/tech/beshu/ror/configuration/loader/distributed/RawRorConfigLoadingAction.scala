package tech.beshu.ror.configuration.loader.distributed

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.configuration.loader.{LoadRawRorConfig, LoadedConfig}
import tech.beshu.ror.configuration.{Compiler, ConfigLoading, IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.providers.EnvVarsProvider

object RawRorConfigLoadingAction{
  def load(esConfigPath: java.nio.file.Path,
           indexJsonContentService: IndexJsonContentService)
          (implicit envVarsProvider: EnvVarsProvider): Task[Either[LoadedConfig.Error, LoadedConfig[RawRorConfig]]] = {
    val compiler = Compiler.create(new IndexConfigManager(indexJsonContentService))
    (for {
      esConfig <- EitherT(ConfigLoading.loadEsConfig(esConfigPath))
      loadedConfig <- EitherT(LoadRawRorConfig.load(esConfigPath, esConfig, esConfig.rorIndex.index))
    } yield loadedConfig).value.foldMap(compiler)
  }

}
