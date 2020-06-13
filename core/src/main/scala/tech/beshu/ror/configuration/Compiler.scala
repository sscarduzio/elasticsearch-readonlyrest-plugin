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

import cats.data.EitherT
import cats.implicits._
import cats.~>
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import shapeless.{Inl, Inr}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.configuration.ConfigLoading.LoadA
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.loader.{FileConfigLoader, LoadedConfig}
import tech.beshu.ror.configuration.loader.LoadedConfig.FileRecoveredConfig.Cause
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig, IndexParsingError}
import tech.beshu.ror.es.IndexJsonContentService

import concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

object Compiler extends Logging {
  def create(indexContentManager: IndexJsonContentService): (LoadA ~> Task) = new (LoadA ~> Task) {
    override def apply[A](fa: LoadA[A]): Task[A] = fa match {
      case ConfigLoading.ForceLoadFromFile(path) =>
        logger.info(s"Loading ReadonlyREST settings from file: $path")
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, ForcedFileConfig(_))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: ${error}")
            error
          }.value
      case ConfigLoading.RecoverIndexWithFile(path, loadingFromIndexCause) =>
        logger.info(s"Loading ReadonlyREST settings from file: $path, due to error $loadingFromIndexCause")
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertRecoveredFileError(loadingFromIndexCause), FileRecoveredConfig(_, loadingFromIndexCause))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: ${error}")
            error
          }
          .value
      case ConfigLoading.LoadFromIndex(index) =>
        logger.info("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index ...")
        loadFromIndex(indexContentManager, index)
          .bimap(convertIndexError, IndexConfig(index, _))
          .leftMap { error =>
            logIndexLoadingError(error)
            error
          }.value
    }
  }

  private def logIndexLoadingError[A](error: Cause): Unit = {
    error match {
      case Inl(FileRecoveredConfig.IndexNotExist) =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
      case Inr(Inl(FileRecoveredConfig.IndexUnknownStructure)) =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case Inr(Inr(Inl(IndexParsingError(message)))) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: $message")
      case Inr(Inr(Inr(_))) => throw new IllegalStateException("couldn't happen")
    }
  }

  private def loadFromIndex[A](indexContentManager: IndexJsonContentService, index: domain.IndexName) = {
    EitherT(new IndexConfigManager(indexContentManager, RorIndexNameConfiguration(index)).load().delayExecution(5 second))
  }

  private def convertRecoveredFileError(cause: FileRecoveredConfig.Cause)
                                       (error: ConfigLoaderError[FileConfigError]): LoadedConfig.Error = {
    cause match {
      case Inl(FileRecoveredConfig.IndexNotExist)
           | Inr(Inl(FileRecoveredConfig.IndexUnknownStructure)) => convertFileError(error)
      case Inr(Inr(Inl(err@IndexParsingError(_)))) => err
      case Inr(Inr(Inr(_))) => throw new IllegalStateException("couldn't happen")
    }
  }

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
      case ParsingError(error) => FileRecoveredConfig.indexParsingError(LoadedConfig.IndexParsingError(error.show))
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => FileRecoveredConfig.indexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => FileRecoveredConfig.indexUnknownStructure
    }

}
