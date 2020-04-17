package tech.beshu.ror.es.rrconfig

import cats.data.EitherT
import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.env.Environment
import tech.beshu.ror.boot.{ComposedConfigLoader, ComposedConfigLoaderFactory}
import tech.beshu.ror.es.providers.EsIndexJsonContentProvider
import tech.beshu.ror.providers.EnvVarsProvider

final class ConfigLoader(env: Environment,
                         indexContentProvider: EsIndexJsonContentProvider)
                        (implicit scheduler: Scheduler,
                         envVarsProvider: EnvVarsProvider) {
  private val composedLoaderMemoized: EitherT[Task, LoadedConfig.Error, ComposedConfigLoader] =
    createLoader()
      .semiflatMap(memoizeOnSuccess)

  private def memoizeOnSuccess[A](a: A): Task[A] =
    Task.pure(a).memoizeOnSuccess


  private def createLoader(): EitherT[Task, LoadedConfig.Error, ComposedConfigLoader] =
    EitherT(new ComposedConfigLoaderFactory(env.configFile(), indexContentProvider).create())
      .leftMap(ConfigLoaderConverter.convert)


  def load(): Task[Either[LoadedConfig.Error, LoadedConfig]] =
    composedLoaderMemoized
      .flatMap(load)
      .value

  private def load(loader: ComposedConfigLoader): EitherT[Task, LoadedConfig.Error, LoadedConfig] =
    EitherT(loader.loadConfig()).bimap(ConfigLoaderConverter.convert,ConfigLoaderConverter.convert)

}

object ConfigLoader {
  sealed trait Error
  final case class FactoryError(message:String) extends Error
  final case class LoadingError(message:String) extends Error

}