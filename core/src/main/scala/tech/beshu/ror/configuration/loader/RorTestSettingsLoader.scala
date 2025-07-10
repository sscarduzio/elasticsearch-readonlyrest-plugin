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
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadFromIndexSettings
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.TestRorSettings
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.RorTestSettingsLoader.{IndexNotExist, IndexParsingError, IndexUnknownStructure, LoadingIndexError}
import tech.beshu.ror.implicits.*

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class RorTestSettingsLoader(indexConfigManager: IndexSettingsManager[TestRorSettings])
  extends Logging {

  def loadFromIndexWithFallback(indexLoadingSettings: LoadFromIndexSettings,
                                fallbackConfig: TestRorSettings): Task[Either[LoadingIndexError, TestRorSettings]] = {
    attemptLoadingConfigFromIndex(
      settings = indexLoadingSettings,
      fallback = fallbackConfig
    )
  }

  private def attemptLoadingConfigFromIndex(settings: LoadFromIndexSettings,
                                            fallback: TestRorSettings): Task[Either[LoadingIndexError, TestRorSettings]] = {
    settings.loadingAttemptsCount.value.value match {
      case 0 =>
        Task.now(Right(fallback))
      case attemptsCount =>
        for {
          result <- loadTestRorConfigFromIndex(
            settings.copy(loadingDelay = LoadingDelay.unsafeFrom(0 seconds))
          )
          rawRorConfig <- result match {
            case Left(IndexNotExist) =>
              attemptLoadingConfigFromIndex(
                settings.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(settings.loadingAttemptsCount.value.value - 1)),
                fallback = fallback
              )
            case Left(error@IndexUnknownStructure) =>
              Task.now(Left(error))
            case Left(error@IndexParsingError(_)) =>
              Task.now(Left(error))
            case Right(value) =>
              Task.now(Right(value))
          }
        } yield rawRorConfig
    }
  }

  private def loadTestRorConfigFromIndex(settings: LoadFromIndexSettings) = {
    val rorConfigIndex = settings.rorConfigIndex
    val loadingDelay = settings.loadingDelay
    logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST test settings from index (${rorConfigIndex.index.show}) ...")
    EitherT {
      indexConfigManager
        .load(rorConfigIndex)
        .delayExecution(loadingDelay.value.value)
    }
      .map { testConfig =>
        testConfig match {
          case TestRorSettings.Present(rawConfig, _, _) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Loaded raw test config from index: ${rawConfig.raw.show}")
          case TestRorSettings.NotSet =>
            logger.debug("[CLUSTERWIDE SETTINGS] There was no test settings in index. Test settings engine will be not initialized.")
        }
        testConfig
      }
      .leftMap { error =>
        val newError = convertIndexError(error)
        logIndexLoadingError(newError)
        newError
      }
      .value
  }

  private def convertIndexError(error: RorSettingsLoader.Error[LoadingIndexSettingsError]): LoadingIndexError =
    error match {
      case ParsingError(error) => IndexParsingError(error.show)
      case SpecializedError(LoadingIndexSettingsError.IndexNotExist) => IndexNotExist
      case SpecializedError(LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => IndexUnknownStructure
    }

  private def logIndexLoadingError(error: LoadingIndexError): Unit = {
    error match {
      case IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case IndexUnknownStructure =>
        logger.info("Loading ReadonlyREST test settings from index failed: index content malformed")
      case IndexNotExist =>
        logger.info("Loading ReadonlyREST test settings from index failed: cannot find index")
    }
  }

}

object RorTestSettingsLoader {
  sealed trait LoadingIndexError
  final case class IndexParsingError(message: String) extends LoadingIndexError
  case object IndexUnknownStructure extends LoadingIndexError
  case object IndexNotExist extends LoadingIndexError
}