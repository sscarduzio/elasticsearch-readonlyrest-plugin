package tech.beshu.ror.configuration

import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig}
import language.implicitConversions

package object loader {
  implicit def toJava(path: tech.beshu.ror.configuration.loader.Path): java.nio.file.Path = java.nio.file.Paths.get(path.value)

  implicit def toDomain(path: java.nio.file.Path): tech.beshu.ror.configuration.loader.Path = tech.beshu.ror.configuration.loader.Path(path.toString)

  implicit class LoadedConfigOps[A](fa: LoadedConfig[A]) {
    lazy val value: A = fa match {
      case FileRecoveredConfig(value, _) => value
      case ForcedFileConfig(value) => value
      case IndexConfig(value) => value
    }

    def map[B](f: A => B): LoadedConfig[B] = fa match {
      case FileRecoveredConfig(value, cause) => FileRecoveredConfig(f(value), cause)
      case ForcedFileConfig(value) => ForcedFileConfig(f(value))
      case IndexConfig(value) => IndexConfig(f(value))
    }
  }
}
