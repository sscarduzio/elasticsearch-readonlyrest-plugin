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

import java.nio.file.Paths

import cats.data.EitherT
import cats.free.Free
import cats.implicits._
import cats.~>
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.LLoader.LoadA
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.{LoadedConfig, Path}
import tech.beshu.ror.es.IndexJsonContentManager

import scala.language.{higherKinds, implicitConversions}

object LLoader {
  type ErrorOr[A] = LoadedConfig.Error Either A
  type Fallback[A] = FileRecoveredConfig.Cause => Load[ErrorOr[FileRecoveredConfig[A]]]
  type Load[A] = Free[LoadA, A]
  sealed trait LoadA[A]
  case class ForceLoadFromFile(path: Path) extends LoadA[ErrorOr[ForcedFileConfig[RawRorConfig]]]
  case class RecoverIndexWithFile(path: Path, loadingFromIndexCause: FileRecoveredConfig.Cause) extends LoadA[ErrorOr[FileRecoveredConfig[RawRorConfig]]]
  case class LoadFromIndex(index: IndexName) extends LoadA[FileRecoveredConfig.Cause Either IndexConfig[RawRorConfig]]


  def program(isLoadingFromFileForced: Boolean,
              configFile: Path,
              configIndex: IndexName,
              indexLoadingAttempts: Int): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    for {
      loadedFileOrIndex <- if (isLoadingFromFileForced) {
        forceLoadFromFile(configFile)
      } else {
        attemptLoadingConfigFromIndex(configIndex, indexLoadingAttempts, recoverIndexWithFile(configFile, _))
      }
    } yield loadedFileOrIndex
  }

  private def attemptLoadingConfigFromIndex(index: IndexName,
                                            attempts: Int,
                                            fallback: Fallback[RawRorConfig]): Load[ErrorOr[LoadedConfig[RawRorConfig]]] = {
    if (attempts <= 1) {
      loadFromIndex(index)
      for {
        result <- loadFromIndex(index)
        rrc <- result match {
          case Left(value) => fallback(value)
          case Right(value) =>
            Free.pure[LoadA, ErrorOr[LoadedConfig[RawRorConfig]]](Right(value))
        }
      } yield rrc
    } else {
      for {
        result <- loadFromIndex(index)
        rrc <- result match {
          case Left(_) => Free.defer(attemptLoadingConfigFromIndex(index, attempts - 1, fallback(_)))
          case Right(value) =>
            Free.pure[LoadA, ErrorOr[LoadedConfig[RawRorConfig]]](Right(value))
        }
      } yield rrc
    }
  }

  def loadFromIndex(index: IndexName): Load[FileRecoveredConfig.Cause Either IndexConfig[RawRorConfig]] =
    Free.liftF(LoadFromIndex(index))

  def recoverIndexWithFile(path: Path,
                           loadingFromIndexCause: FileRecoveredConfig.Cause): Load[ErrorOr[FileRecoveredConfig[RawRorConfig]]] =
    Free.liftF(RecoverIndexWithFile(path, loadingFromIndexCause))

  def forceLoadFromFile(path: Path): Load[ErrorOr[ForcedFileConfig[RawRorConfig]]] =
    Free.liftF(ForceLoadFromFile(path))

}
object Compiler{
  def create(indexContentManager: IndexJsonContentManager): (LoadA~>Task) = new (LoadA~>Task) {
    override def apply[A](fa: LoadA[A]): Task[A] = fa match {
      case LLoader.ForceLoadFromFile(path) =>
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, ForcedFileConfig(_))
          .value
      case LLoader.RecoverIndexWithFile(path, loadingFromIndexCause) =>
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, FileRecoveredConfig(_, loadingFromIndexCause))
          .value
      case LLoader.LoadFromIndex(index) =>
        val indexConf = RorIndexNameConfiguration(index)
        EitherT(new IndexConfigManager(indexContentManager, indexConf).load())
          .bimap(convertIndexError, IndexConfig(_))
          .value
    }
  }
  implicit private def toJava(path: tech.beshu.ror.configuration.loader.Path):java.nio.file.Path = Paths.get(path.value)
  implicit private def toDomain(path: java.nio.file.Path):Path = Path(path.toString)

  private def convertFileError(error: ConfigLoaderError[FileConfigError]): LoadedConfig.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        LoadedConfig.FileParsingError(show)
      case SpecializedError(FileConfigError.FileNotExist(file)) => LoadedConfig.FileNotExist(file.path)
    }
  }
  private def convertIndexError(error: ConfigLoaderError[IndexConfigManager.IndexConfigError]): FileRecoveredConfig.Cause =
    error match {
      case ParsingError(error) =>  FileRecoveredConfig.indexParsingError(LoadedConfig.IndexParsingError(error.show))
      case SpecializedError(IndexConfigError.IndexConfigNotExist) =>  FileRecoveredConfig.indexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => FileRecoveredConfig.indexUnknownStructure
    }

}
