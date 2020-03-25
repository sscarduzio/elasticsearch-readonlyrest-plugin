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
package tech.beshu.ror.boot

import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.RawRorConfig.ParsingRorConfigError
import tech.beshu.ror.configuration.{ConfigLoader, EsConfig, RawRorConfig}

import scala.concurrent.duration._
import scala.language.postfixOps

final class ComposedConfigLoader(fileConfigLoader: ConfigLoader[FileConfigError],
                                 indexConfigManager: ConfigLoader[IndexConfigError],
                                 esConfig: EsConfig,
                                ) extends Logging {

  import ComposedConfigLoader._

  def loadConfig(): Task[Either[LoadedConfig.Error, LoadedConfig]] = {
    if (esConfig.rorEsLevelSettings.forceLoadRorFromFile) {
      loadRorConfigFromFile(fileConfigLoader).map(ComposedConfigLoader.ForcedFile)
        .bimap(identity, identity)
        .value
    } else {
      loadRorConfigFromIndex(indexConfigManager)
        .leftFlatMap(fallback(loadRorConfigFromFile(fileConfigLoader)))
        .value
    }
  }

  def fallback(value: EitherT[Task, ComposedConfigLoader.File.Error, RawRorConfig])
              (indexConfigError: ComposedConfigLoader.Index.Error): EitherT[Task, ComposedConfigLoader.LoadedConfig.Error, ComposedConfigLoader.LoadedConfig] = {
    def fallback: EitherT[Task, ComposedConfigLoader.LoadedConfig.Error, ComposedConfigLoader.LoadedConfig] =
      value.map(ComposedConfigLoader.FileRecoveredIndex(_, indexConfigError))
        .bimap(identity,identity)

    indexConfigError match {
      case parsingError@Index.ParsingError(_) => EitherT.leftT(parsingError)
      case error@Index.IndexConfigNotExist => fallback
      case error@Index.IndexConfigUnknownStructure => fallback
    }
  }


  private def loadRorConfigFromFile(fileConfigLoader: ConfigLoader[FileConfigError]): EitherT[Task, ComposedConfigLoader.File.Error, RawRorConfig] = EitherT {
    logger.info(s"Loading ReadonlyREST settings from file: ${fileConfigLoader}")
    fileConfigLoader
      .load()
  }.leftMap(convertFileError)

  private def convertFileError(error: ConfigLoaderError[FileConfigError]): ComposedConfigLoader.File.Error = {
    error match {
      case ParsingError(error) => ComposedConfigLoader.File.ParsingError(error)
      case SpecializedError(FileConfigError.FileNotExist(file)) => ComposedConfigLoader.File.FileNotExist(file)
    }
  }

  private def loadRorConfigFromIndex(indexConfigManager: ConfigLoader[IndexConfigError]): EitherT[Task, Index.Error, ComposedConfigLoader.Index] = {
    def attempt(attemptsLeft: Int,
                startingFailure: Option[ConfigLoaderError[IndexConfigError]] = None): Task[Either[ConfigLoaderError[IndexConfigError], RawRorConfig]] = {
      val executionDelay = startingFailure match {
        case None => 1 second
        case Some(_) => 5 seconds
      }
      startingFailure match {
        case Some(failure) if attemptsLeft <= 0 =>
          Task.now(Left(failure))
        case None | Some(_) =>
          indexConfigManager
            .load()
            .delayExecution(executionDelay)
            .flatMap {
              case Right(success) => Task.now(Right(success))
              case Left(failure) => attempt(attemptsLeft - 1, Some(failure))
            }
      }
    }

    logger.info("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index ...")
    val result = attempt(5)
    EitherT(result)
      .bimap(convertIndexError, ComposedConfigLoader.Index(_))
  }

  private def convertIndexError(err: ConfigLoaderError[IndexConfigError]) = {
    err match {
      case ParsingError(error) => Index.ParsingError(error)
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => Index.IndexConfigNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => Index.IndexConfigUnknownStructure
    }
  }

}

object ComposedConfigLoader {
  sealed trait LoadedConfig
  final case class FileRecoveredIndex(config: RawRorConfig, error:Index.Error) extends LoadedConfig
  case class Index(config: RawRorConfig) extends LoadedConfig
  final case class ForcedFile(config: RawRorConfig) extends LoadedConfig
  object LoadedConfig {
    sealed trait Error
  }
  object File {
    sealed trait Error extends LoadedConfig.Error
    final case class ParsingError(underlying: ParsingRorConfigError) extends File.Error
    final case class FileNotExist(file: better.files.File) extends File.Error
  }
  object Index {
    sealed trait Error
    final case class ParsingError(underlying: ParsingRorConfigError) extends Index.Error with LoadedConfig.Error
    case object IndexConfigNotExist extends Index.Error
    case object IndexConfigUnknownStructure extends Index.Error
  }
}