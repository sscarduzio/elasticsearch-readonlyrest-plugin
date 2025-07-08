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
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.{LoadFromFileSettings, LoadFromIndexSettings}
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError
import tech.beshu.ror.configuration.loader.LoadedRorConfig.IndexParsingError
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.{RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

// todo: indexConfigManager should be passed in constructor?
// todo: refactor methods
object LoadRawRorConfig extends Logging {

  def loadFromIndexWithFileFallback(indexLoadingSettings: LoadFromIndexSettings,
                                    fallbackFileLoadingSettings: LoadFromFileSettings,
                                    indexConfigManager: IndexSettingsManager[RawRorSettings]): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    attemptLoadingConfigFromIndex(
      settings = indexLoadingSettings,
      fallback = loadRorConfigFromFile(fallbackFileLoadingSettings),
      indexConfigManager
    )
  }

  def loadFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    forceLoadRorConfigFromFile(settings)
  }

  def loadFromIndex(settings: LoadFromIndexSettings,
                    indexConfigManager: IndexSettingsManager[RawRorSettings]): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    for {
      // todo: is the copy ok?
      result <- loadRorConfigFromIndex(
        settings.copy(loadingDelay = LoadingDelay.unsafeFrom(0 seconds)),
        indexConfigManager
      )
      rawRorConfig <- result match {
        case Left(LoadedRorConfig.IndexNotExist) =>
          Task.now(Left(LoadedRorConfig.IndexNotExist: LoadedRorConfig.Error))
        case Left(LoadedRorConfig.IndexUnknownStructure) =>
          Task.now(Left(LoadedRorConfig.IndexUnknownStructure: LoadedRorConfig.Error))
        case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
          Task.now(Left(error: LoadedRorConfig.Error))
        case Right(value) =>
          Task.now(Right(value))
      }
    } yield rawRorConfig
  }

  private def attemptLoadingConfigFromIndex(settings: LoadFromIndexSettings,
                                            fallback: Task[Either[LoadedRorConfig.Error, RawRorSettings]],
                                            indexConfigManager: IndexSettingsManager[RawRorSettings]): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    settings.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        for {
          result <- loadRorConfigFromIndex(settings, indexConfigManager)
          rawRorConfig <- result match {
            case Left(LoadedRorConfig.IndexNotExist) =>
              attemptLoadingConfigFromIndex(
                settings.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(settings.loadingAttemptsCount.value.value - 1)),
                fallback = fallback,
                indexConfigManager
              )
            case Left(LoadedRorConfig.IndexUnknownStructure) =>
              Task.now(Left(LoadedRorConfig.IndexUnknownStructure))
            case Left(error@LoadedRorConfig.IndexParsingError(_)) =>
              Task.now(Left(error))
            case Right(value) =>
              Task.now(Right(value))
          }
        } yield rawRorConfig
    }
  }

  private def loadRorConfigFromIndex(settings: LoadFromIndexSettings,
                                     indexConfigManager: IndexSettingsManager[RawRorSettings]) = {
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
      .leftMap { error =>
        val newError = convertIndexError(error)
        logIndexLoadingError(newError)
        newError
      }
      .value
  }

  private def loadRorConfigFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    val rorSettingsFile = settings.rorSettingsFile
    val rawRorConfigYamlParser = new RawRorSettingsYamlParser(settings.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings from file from: ${rorSettingsFile.show}, because index not exist")
    EitherT(new FileRorSettingsLoader(rorSettingsFile, rawRorConfigYamlParser).load())
      .leftMap { error =>
        val newError = convertFileError(error)
        logger.error(s"Loading ReadonlyREST from file failed: ${newError.toString}")
        newError
      }
      .value
  }

  private def forceLoadRorConfigFromFile(settings: LoadFromFileSettings): Task[Either[LoadedRorConfig.Error, RawRorSettings]] = {
    val rorSettingsFile = settings.rorSettingsFile
    val rawRorConfigYamlParser = new RawRorSettingsYamlParser(settings.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings forced loading from file from: ${rorSettingsFile.show}")
    EitherT(new FileRorSettingsLoader(rorSettingsFile, rawRorConfigYamlParser).load())
      .leftMap { error =>
        val newError = convertFileError(error)
        logger.error(s"Loading ReadonlyREST from file failed: ${newError.toString}")
        newError
      }
      .value
  }

  private def convertFileError(error: RorSettingsLoader.Error[FileRorSettingsLoader.Error]): LoadedRorConfig.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        LoadedRorConfig.FileParsingError(show)
      case SpecializedError(FileRorSettingsLoader.Error.FileNotExist(file)) => LoadedRorConfig.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: RorSettingsLoader.Error[LoadingIndexSettingsError]) =
    error match {
      case ParsingError(error) => LoadedRorConfig.IndexParsingError(error.show)
      case SpecializedError(LoadingIndexSettingsError.IndexNotExist) => LoadedRorConfig.IndexNotExist
      case SpecializedError(LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => LoadedRorConfig.IndexUnknownStructure
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
