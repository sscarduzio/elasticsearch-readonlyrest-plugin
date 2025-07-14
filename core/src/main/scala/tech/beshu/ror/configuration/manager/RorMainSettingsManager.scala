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
package tech.beshu.ror.configuration.manager

import better.files.File
import cats.data.EitherT
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.{LoadFromFileParameters, LoadFromIndexParameters, LoadingRorCoreStrategy}
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.{FileRorSettingsLoader, RorSettingsLoader}
import tech.beshu.ror.configuration.manager.RorMainSettingsManager.LoadingFromFileError
import tech.beshu.ror.configuration.manager.SettingsManager.{LoadingError, LoadingFromIndexError, SavingIndexSettingsError}
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*

import scala.language.postfixOps

// todo: refactor methods
class RorMainSettingsManager private(indexSettingsManager: IndexSettingsManager[RawRorSettings])
  extends SettingsManager[RawRorSettings] with Logging {

  def loadFromFile(loadFromFileParameters: LoadFromFileParameters): Task[Either[LoadingFromFileError, RawRorSettings]] = {
    forceLoadFromFile(loadFromFileParameters)
  }

  override def loadFromIndex(): Task[Either[LoadingFromIndexError, RawRorSettings]] = {
    loadRorSettingsFromIndex(loadingDelay = LoadingDelay.none)
  }

  override def loadFromIndexWithFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                         fallback: Task[Either[LoadingError, RawRorSettings]]): Task[Either[LoadingError, RawRorSettings]] = {
    attemptLoadingFromIndex(
      parameters = loadFromIndexParameters,
      fallback = fallback
    )
  }

  def loadFromIndexWithFileFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                    loadFromFileParameters: LoadFromFileParameters): Task[Either[LoadingError, RawRorSettings]] = {
    loadFromIndexWithFallback(
      loadFromIndexParameters,
      loadRorSettingsFromFile(loadFromFileParameters)
    )
  }

  override def saveToIndex(settings: RawRorSettings): Task[Either[SavingIndexSettingsError, Unit]] = {
    EitherT(indexSettingsManager.save(settings))
      .leftMap {
        case IndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings => SettingsManager.SavingIndexSettingsError.CannotSaveSettings
      }
      .value
  }

  private def attemptLoadingFromIndex(parameters: LoadFromIndexParameters,
                                      fallback: Task[Either[LoadingError, RawRorSettings]]): Task[Either[LoadingError, RawRorSettings]] = {
    parameters.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        loadRorSettingsFromIndex(parameters.loadingDelay).flatMap {
          case Left(LoadingFromIndexError.IndexNotExist) =>
            attemptLoadingFromIndex(
              parameters.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(parameters.loadingAttemptsCount.value.value - 1)),
              fallback = fallback
            )
          case Left(LoadingFromIndexError.IndexUnknownStructure) =>
            Task.now(Left(LoadingFromIndexError.IndexUnknownStructure))
          case Left(error@LoadingFromIndexError.IndexParsingError(_)) =>
            Task.now(Left(error))
          case Right(value) =>
            Task.now(Right(value))
        }
    }
  }

  private def loadRorSettingsFromIndex(loadingDelay: LoadingDelay) = {
    val settingsIndex = indexSettingsManager.settingsIndex
    logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index (${settingsIndex.index.show}) ...")
    EitherT {
      indexSettingsManager
        .load()
        .delayExecution(loadingDelay.value.value)
    }
      .map { rorSettings =>
        logger.debug(s"[CLUSTERWIDE SETTINGS] Loaded raw ReadonlyREST settings from index: ${rorSettings.raw.show}")
        rorSettings
      }
      .leftMap { error =>
        val newError = convertIndexError(error)
        logIndexLoadingError(newError)
        newError
      }
      .value
  }

  // todo: these two are almost the same (logging differs only)
  private def loadRorSettingsFromFile(parameters: LoadFromFileParameters): Task[Either[LoadingError, RawRorSettings]] = {
    val rorSettingsFile = parameters.rorSettingsFile
    val rawRorSettingsYamlParser = new RawRorSettingsYamlParser(parameters.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings from file from: ${rorSettingsFile.show}, because index not exist")
    EitherT(new FileRorSettingsLoader(rorSettingsFile, rawRorSettingsYamlParser).load())
      .leftMap { error =>
        val newError = convertFileError(error)
        logger.error(s"Loading ReadonlyREST from file failed: ${newError.toString}")
        newError
      }
      .value
  }

  private def forceLoadFromFile(parameters: LoadFromFileParameters): Task[Either[LoadingFromFileError, RawRorSettings]] = {
    val rorSettingsFile = parameters.rorSettingsFile
    val rawRorSettingsYamlParser = new RawRorSettingsYamlParser(parameters.settingsMaxSize)
    logger.info(s"Loading ReadonlyREST settings forced loading from file from: ${rorSettingsFile.show}")
    EitherT(new FileRorSettingsLoader(rorSettingsFile, rawRorSettingsYamlParser).load())
      .leftMap { error =>
        val newError = convertFileError(error)
        logger.error(s"Loading ReadonlyREST from file failed: ${newError.toString}")
        newError
      }
      .value
  }

  private def convertFileError(error: RorSettingsLoader.Error[FileRorSettingsLoader.Error]): LoadingFromFileError = {
    error match {
      case ParsingError(error) => LoadingFromFileError.FileParsingError(error.show)
      case SpecializedError(FileRorSettingsLoader.Error.FileNotExist(file)) => LoadingFromFileError.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: RorSettingsLoader.Error[IndexSettingsManager.LoadingIndexSettingsError]) =
    error match {
      case ParsingError(error) => LoadingFromIndexError.IndexParsingError(error.show)
      case SpecializedError(IndexSettingsManager.LoadingIndexSettingsError.IndexNotExist) => LoadingFromIndexError.IndexNotExist
      case SpecializedError(IndexSettingsManager.LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => LoadingFromIndexError.IndexUnknownStructure
    }

  private def logIndexLoadingError[A](error: LoadingFromIndexError): Unit = {
    error match {
      case LoadingFromIndexError.IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case LoadingFromIndexError.IndexUnknownStructure =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case LoadingFromIndexError.IndexNotExist =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
    }
  }
}

object RorMainSettingsManager {

  def create(esConfigBasedRorSettings: EsConfigBasedRorSettings): RorMainSettingsManager = {
    esConfigBasedRorSettings.loadingRorCoreStrategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile(parameters) => ???
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, fallbackParameters) => ???
    }
  }

  sealed trait LoadingFromFileError extends LoadingError
  object LoadingFromFileError {
    final case class FileParsingError(message: String) extends LoadingFromFileError
    final case class FileNotExist(file: File) extends LoadingFromFileError
  }

}