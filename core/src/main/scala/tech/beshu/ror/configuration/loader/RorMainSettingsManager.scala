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
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.{LoadFromFileParameters, LoadFromIndexParameters}
import tech.beshu.ror.configuration.RorProperties.LoadingAttemptsCount
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.{RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.implicits.*

import java.nio.file.Path
import scala.language.postfixOps

// todo: refactor methods
class RorMainSettingsManager(indexSettingsManager: IndexSettingsManager[RawRorSettings])
  extends Logging {

  def loadFromFile(loadFromFileParameters: LoadFromFileParameters): Task[Either[RorMainSettingsManager.Error, RawRorSettings]] = {
    forceLoadFromFile(loadFromFileParameters)
  }

  def loadFromIndexWithFileFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                    loadFromFileParameters: LoadFromFileParameters): Task[Either[RorMainSettingsManager.Error, RawRorSettings]] = {
    attemptLoadingFromIndex(
      parameters = loadFromIndexParameters,
      fallback = loadRorSettingsFromFile(loadFromFileParameters)
    )
  }

  private def attemptLoadingFromIndex(parameters: LoadFromIndexParameters,
                                      fallback: Task[Either[RorMainSettingsManager.Error, RawRorSettings]]): Task[Either[RorMainSettingsManager.Error, RawRorSettings]] = {
    parameters.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        loadRorSettingsFromIndex(parameters).flatMap {
          case Left(RorMainSettingsManager.IndexNotExist) =>
            attemptLoadingFromIndex(
              parameters.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(parameters.loadingAttemptsCount.value.value - 1)),
              fallback = fallback
            )
          case Left(RorMainSettingsManager.IndexUnknownStructure) =>
            Task.now(Left(RorMainSettingsManager.IndexUnknownStructure))
          case Left(error@RorMainSettingsManager.IndexParsingError(_)) =>
            Task.now(Left(error))
          case Right(value) =>
            Task.now(Right(value))
        }
    }
  }

  private def loadRorSettingsFromIndex(parameters: LoadFromIndexParameters) = {
    val rorSettingsIndex = parameters.rorSettingsIndex
    logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST settings from index (${rorSettingsIndex.index.show}) ...")
    EitherT {
      indexSettingsManager
        .load()
        .delayExecution(parameters.loadingDelay.value.value)
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
  private def loadRorSettingsFromFile(parameters: LoadFromFileParameters): Task[Either[RorMainSettingsManager.Error, RawRorSettings]] = {
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

  private def forceLoadFromFile(parameters: LoadFromFileParameters): Task[Either[RorMainSettingsManager.Error, RawRorSettings]] = {
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

  private def convertFileError(error: RorSettingsLoader.Error[FileRorSettingsLoader.Error]): RorMainSettingsManager.Error = {
    error match {
      case ParsingError(error) =>
        val show = error.show
        RorMainSettingsManager.FileParsingError(show)
      case SpecializedError(FileRorSettingsLoader.Error.FileNotExist(file)) => RorMainSettingsManager.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: RorSettingsLoader.Error[LoadingIndexSettingsError]) =
    error match {
      case ParsingError(error) => RorMainSettingsManager.IndexParsingError(error.show)
      case SpecializedError(LoadingIndexSettingsError.IndexNotExist) => RorMainSettingsManager.IndexNotExist
      case SpecializedError(LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => RorMainSettingsManager.IndexUnknownStructure
    }

  private def logIndexLoadingError[A](error: RorMainSettingsManager.LoadingIndexError): Unit = {
    error match {
      case RorMainSettingsManager.IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case RorMainSettingsManager.IndexUnknownStructure =>
        logger.info(s"Loading ReadonlyREST settings from index failed: index content malformed")
      case RorMainSettingsManager.IndexNotExist =>
        logger.info(s"Loading ReadonlyREST settings from index failed: cannot find index")
    }
  }

}
object RorMainSettingsManager {

  // todo: do we need two hierarchies?
  sealed trait Error
  final case class FileParsingError(message: String) extends Error
  final case class FileNotExist(path: Path) extends Error

  sealed trait LoadingIndexError extends Error
  final case class IndexParsingError(message: String) extends Error with LoadingIndexError
  case object IndexUnknownStructure extends Error with LoadingIndexError
  case object IndexNotExist extends Error with LoadingIndexError
}