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
package tech.beshu.ror.configuration.loader

import cats.data.EitherT
import cats.implicits._
import cats.~>
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.domain.RorConfigurationIndex
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.configuration.ConfigLoading.LoadConfigAction
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError.RorSettingsInactiveWhenXpackSecurityIsEnabled.SettingsType
import tech.beshu.ror.configuration.index.{IndexConfigError, IndexConfigManager}
import tech.beshu.ror.configuration.RorProperties.LoadingDelay
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.FileConfigLoader.FileConfigError
import tech.beshu.ror.configuration.loader.LoadedRorConfig._
import tech.beshu.ror.configuration.{ConfigLoading, EsConfig}
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}

object ConfigLoadingInterpreter extends Logging {

  def create(indexConfigManager: IndexConfigManager,
             inIndexLoadingDelay: LoadingDelay)
            (implicit envVarsProvider: EnvVarsProvider,
             propertiesProvider: PropertiesProvider): LoadConfigAction ~> Task = new (LoadConfigAction ~> Task) {
    override def apply[A](fa: LoadConfigAction[A]): Task[A] = fa match {
      case ConfigLoading.LoadConfigAction.LoadEsConfig(esConfigPath) =>
        logger.info(s"Loading Elasticsearch settings from file: ${esConfigPath.value}")
        EsConfig
          .from(esConfigPath)
          .map(_.left.map {
            case LoadEsConfigError.FileNotFound(file) =>
              EsFileNotExist(Path(file.pathAsString))
            case LoadEsConfigError.MalformedContent(file, msg) =>
              EsFileMalformed(Path(file.pathAsString), msg)
            case LoadEsConfigError.RorSettingsInactiveWhenXpackSecurityIsEnabled(aType) =>
              CannotUseRorConfigurationWhenXpackSecurityIsEnabled(
                aType match {
                  case SettingsType.Ssl => "SSL configuration"
                  case SettingsType.Fibs => "FIBS configuration"
                }
              )
          })
      case ConfigLoading.LoadConfigAction.ForceLoadRorConfigFromFile(path) =>
        logger.info(s"Loading ReadonlyREST settings forced loading from file: ${path.value}")
        EitherT(new FileConfigLoader(path).load())
          .bimap(convertFileError, ForcedFileConfig(_))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: $error")
            error
          }.value
      case ConfigLoading.LoadConfigAction.LoadRorConfigFromFile(path) =>
        logger.info(s"Loading ReadonlyREST settings from file: ${path.value}, because index not exist")
        EitherT(new FileConfigLoader(path).load())
          .bimap(convertFileError, FileConfig(_))
          .leftMap { error =>
            logger.error(s"Loading ReadonlyREST from file failed: $error")
            error
          }
          .value
      case ConfigLoading.LoadConfigAction.LoadRorConfigFromIndex(configIndex) =>
        logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index (${configIndex.index.show}) ...")
        loadFromIndex(indexConfigManager, configIndex, inIndexLoadingDelay)
          .map { rawRorConfig =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Loaded raw config from index: ${rawRorConfig.raw}")
            rawRorConfig
          }
          .bimap(convertIndexError, IndexConfig(configIndex, _))
          .leftMap { error =>
            logIndexLoadingError(error)
            error
          }.value
    }
  }

  private def logIndexLoadingError[A](error: LoadedRorConfig.LoadingIndexError): Unit = {
    error match {
      case IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: $message")
      case LoadedRorConfig.IndexUnknownStructure =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case LoadedRorConfig.IndexNotExist =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
    }
  }

  private def loadFromIndex[A](indexConfigManager: IndexConfigManager,
                               index: RorConfigurationIndex,
                               inIndexLoadingDelay: LoadingDelay) = {
    EitherT(indexConfigManager.load(index).delayExecution(inIndexLoadingDelay.duration.value))
  }

  private def convertFileError(error: ConfigLoaderError[FileConfigError]): LoadedRorConfig.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        LoadedRorConfig.FileParsingError(show)
      case SpecializedError(FileConfigError.FileNotExist(file)) => LoadedRorConfig.FileNotExist(Path(file.pathAsString))
    }
  }

  private def convertIndexError(error: ConfigLoaderError[IndexConfigError])=
    error match {
      case ParsingError(error) => LoadedRorConfig.IndexParsingError(error.show)
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => LoadedRorConfig.IndexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => LoadedRorConfig.IndexUnknownStructure
    }

}
