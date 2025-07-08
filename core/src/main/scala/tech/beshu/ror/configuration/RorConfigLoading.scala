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
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.index.{IndexConfigError, IndexConfigManager}
import tech.beshu.ror.configuration.loader.LoadedRorConfig.IndexParsingError
import tech.beshu.ror.configuration.loader.RorConfigLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.{FileRorConfigLoader, LoadedRorConfig, RorConfigLoader}
import tech.beshu.ror.implicits.*

object RorConfigLoading extends Logging {

  def loadRorConfigFromIndex(settings: LoadFromIndexSettings, indexConfigManager: IndexConfigManager): Task[Either[LoadedRorConfig.Error & LoadedRorConfig.LoadingIndexError, LoadedRorConfig[RawRorConfig]]] = {
    val rorConfigIndex = settings.rorConfigIndex
    logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index (${rorConfigIndex.index.show}) ...")
    EitherT {
      indexConfigManager
        .load(settings.rorConfigIndex)
        .delayExecution(settings.loadingDelay.value.value)
    }.map { rawRorConfig =>
        logger.debug(s"[CLUSTERWIDE SETTINGS] Loaded raw config from index: ${rawRorConfig.raw.show}")
        rawRorConfig
      }
      .bimap(convertIndexError, LoadedRorConfig.apply)
      .leftMap { error =>
        logIndexLoadingError(error)
        error
      }.value
  }

  def loadRorConfigFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    val rorSettingsFile = settings.rorSettingsFile
    val rawRorConfigYamlParser = new RawRorConfigYamlParser(settings.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings from file from: ${rorSettingsFile.show}, because index not exist")
    EitherT(new FileRorConfigLoader(rorSettingsFile, rawRorConfigYamlParser).load())
      .bimap(convertFileError, LoadedRorConfig.apply)
      .leftMap { error =>
        logger.error(s"Loading ReadonlyREST from file failed: ${error.toString}")
        error
      }
      .value
  }

  def forceLoadRorConfigFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, LoadedRorConfig[RawRorConfig]]] = {
    val rorSettingsFile = settings.rorSettingsFile
    val rawRorConfigYamlParser = new RawRorConfigYamlParser(settings.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings forced loading from file from: ${rorSettingsFile.show}")
    EitherT(new FileRorConfigLoader(rorSettingsFile, rawRorConfigYamlParser).load())
      .bimap(convertFileError, LoadedRorConfig.apply)
      .leftMap { error =>
        logger.error(s"Loading ReadonlyREST from file failed: ${error.toString}")
        error
      }.value
  }
//    Free.liftF(LoadRorConfigAction.ForceLoadRorConfigFromFile(settings))

  private def convertFileError(error: RorConfigLoader.Error[FileRorConfigLoader.Error]): LoadedRorConfig.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        LoadedRorConfig.FileParsingError(show)
      case SpecializedError(FileRorConfigLoader.Error.FileNotExist(file)) => LoadedRorConfig.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: RorConfigLoader.Error[IndexConfigError]) =
    error match {
      case ParsingError(error) => LoadedRorConfig.IndexParsingError(error.show)
      case SpecializedError(IndexConfigError.IndexConfigNotExist) => LoadedRorConfig.IndexNotExist
      case SpecializedError(IndexConfigError.IndexConfigUnknownStructure) => LoadedRorConfig.IndexUnknownStructure
    }

  private def logIndexLoadingError[A](error: LoadedRorConfig.LoadingIndexError): Unit = {
    error match {
      case IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case LoadedRorConfig.IndexUnknownStructure =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case LoadedRorConfig.IndexNotExist =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
    }
  }

}
