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
import tech.beshu.ror.configuration.ConfigLoading.LoadA
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.IndexConfigManager.IndexConfigError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.loader.LoadedConfig._
import tech.beshu.ror.configuration.loader.{FileConfigLoader, LoadedConfig, RorConfigurationIndex}
import tech.beshu.ror.providers.EnvVarsProvider

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

object Compiler extends Logging {
  def create(indexConfigManager: IndexConfigManager)
            (implicit envVarsProvider: EnvVarsProvider): (LoadA ~> Task) = new (LoadA ~> Task) {
    override def apply[A](fa: LoadA[A]): Task[A] = fa match {
      case ConfigLoading.LoadEsConfig(esConfigPath) =>
        EsConfig
          .from(esConfigPath)
          .map(_.left.map {
            case LoadEsConfigError.FileNotFound(file) =>
              EsFileNotExist(file.toJava.toPath)
            case LoadEsConfigError.MalformedContent(file, msg) =>
              EsFileMalformed(file.toJava.toPath, msg)
          })
      case ConfigLoading.ForceLoadFromFile(path) =>
        logger.info(s"Loading ReadonlyREST settings from file: $path")
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, ForcedFileConfig(_))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: ${error}")
            error
          }.value
      case ConfigLoading.LoadFromFile(path) =>
        logger.info(s"Loading ReadonlyREST settings from file: $path, because index not exist")
        EitherT(FileConfigLoader.create(path).load())
          .bimap(convertFileError, FileConfig(_))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: ${error}")
            error
          }
          .value
      case ConfigLoading.LoadFromIndex(index) =>
        logger.info("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index ...")
        loadFromIndex(indexConfigManager, index)
          .bimap(convertIndexError, IndexConfig(index, _))
          .leftMap { error =>
            logIndexLoadingError(error)
            error
          }.value
    }
  }

  private def logIndexLoadingError[A](error: LoadedConfig.LoadingIndexError): Unit = {
    error match {
      case IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: $message")
      case LoadedConfig.IndexUnknownStructure =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case LoadedConfig.IndexNotExist =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
    }
  }

  private def loadFromIndex[A](indexConfigManager: IndexConfigManager, index: RorConfigurationIndex) = {
    EitherT(indexConfigManager.load(index).delayExecution(5 second))
  }

  private def convertFileError(error: ConfigLoaderError[FileConfigError]): LoadedConfig.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        LoadedConfig.FileParsingError(show)
      case SpecializedError(FileConfigError.FileNotExist(file)) => LoadedConfig.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: ConfigLoaderError[IndexConfigManager.IndexConfigError])=
    error match {
      case ParsingError(error) => LoadedConfig.IndexParsingError(error.show)
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => LoadedConfig.IndexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => LoadedConfig.IndexUnknownStructure
    }

}
