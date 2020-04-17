package tech.beshu.ror.es.rrconfig

import cats.implicits._
import shapeless._
import tech.beshu.ror.boot.ComposedConfigLoader.Index
import tech.beshu.ror.boot.{ComposedConfigLoader, ComposedConfigLoaderFactory}
import tech.beshu.ror.es.rrconfig
import tech.beshu.ror.es.rrconfig.LoadedConfig.FileRecoveredConfig.{IndexNotExist, IndexUnknownStructure}
import tech.beshu.ror.es.rrconfig.LoadedConfig._

object ConfigLoaderConverter {

  def convert(loadedConfigEither: Either[ComposedConfigLoader.LoadedConfig.Error, ComposedConfigLoader.LoadedConfig]): Either[LoadedConfig.Error, LoadedConfig] = {

    loadedConfigEither.bimap(convert, convert)
  }

  def convert(value: ComposedConfigLoader.LoadedConfig): LoadedConfig = {
    value match {
      case ComposedConfigLoader.FileRecoveredIndex(config, error) => FileRecoveredConfig(config.raw, convert(error))
      case ComposedConfigLoader.Index(config) => IndexConfig(config.raw)
      case ComposedConfigLoader.ForcedFile(config) => ForcedFileConfig(config.raw)
    }
  }

  private def convert(error: Index.Error): FileRecoveredConfig.Cause = {
    error match {
      case Index.ParsingError(underlying) => Coproduct[FileRecoveredConfig.Cause](IndexParsingError(underlying.show))
      case Index.IndexConfigNotExist => Coproduct[FileRecoveredConfig.Cause](IndexUnknownStructure)
      case Index.IndexConfigUnknownStructure => Coproduct[FileRecoveredConfig.Cause](IndexNotExist)
    }
  }

  def convert(error: ComposedConfigLoader.LoadedConfig.Error): LoadedConfig.Error = {
    error match {
      case ComposedConfigLoader.File.ParsingError(underlying) => FileParsingError(underlying.show)
      case ComposedConfigLoader.File.FileNotExist(file) => FileNotExist(convert(file))
      case ComposedConfigLoader.Index.ParsingError(underlying) => IndexParsingError(underlying.show)
    }
  }
  def convert(error: ComposedConfigLoaderFactory.LoadingError): LoadedConfig.Error = {
    error match {
      case ComposedConfigLoaderFactory.FileNotFound(file) => EsFileNotExist(convert(file))
      case ComposedConfigLoaderFactory.FileContentMalformed(file, message) => EsFileMalformed(convert(file),message)
      case ComposedConfigLoaderFactory.IndexConfigurationMalformed(message) => EsIndexConfigurationMalformed(message)
    }
  }

  private def convert(file:better.files.File) =
    rrconfig.Path(file.pathAsString)
}
