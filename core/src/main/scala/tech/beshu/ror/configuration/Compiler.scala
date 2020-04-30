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
import cats.implicits._
import cats.~>
import monix.eval.Task
import tech.beshu.ror.configuration.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.ConfigLoading.LoadA
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.LoadedConfig.{FileRecoveredConfig, ForcedFileConfig, IndexConfig}
import tech.beshu.ror.configuration.loader.{LoadedConfig, Path}
import tech.beshu.ror.es.IndexJsonContentManager

import scala.language.implicitConversions

object Compiler{
  def create(indexContentManager: IndexJsonContentManager): (LoadA~>Task) = new (LoadA~>Task) {
    override def apply[A](fa: LoadA[A]): Task[A] = fa match {
      case ConfigLoading.ForceLoadFromFile(path) =>
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, ForcedFileConfig(_))
          .value
      case ConfigLoading.RecoverIndexWithFile(path, loadingFromIndexCause) =>
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, FileRecoveredConfig(_, loadingFromIndexCause))
          .value
      case ConfigLoading.LoadFromIndex(index) =>
        val indexConf = RorIndexNameConfiguration(index)
        EitherT(new IndexConfigManager(indexContentManager, indexConf).load())
          .bimap(convertIndexError, IndexConfig(_))
          .value
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
      case ParsingError(error) =>  FileRecoveredConfig.indexParsingError(LoadedConfig.IndexParsingError(error.show))
      case SpecializedError(IndexConfigError.IndexConfigNotExist) =>  FileRecoveredConfig.indexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => FileRecoveredConfig.indexUnknownStructure
    }

}
