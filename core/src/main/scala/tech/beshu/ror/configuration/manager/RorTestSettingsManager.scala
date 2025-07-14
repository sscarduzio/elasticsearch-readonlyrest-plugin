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

import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadFromIndexParameters
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingDelay}
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, TestRorSettings}
import tech.beshu.ror.configuration.index.IndexSettingsManager
import tech.beshu.ror.configuration.index.IndexSettingsManager.LoadingIndexSettingsError
import tech.beshu.ror.configuration.loader.{FileRorSettingsLoader, RorSettingsLoader}
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.manager.SettingsManager.{LoadingError, LoadingFromIndexError, SavingIndexSettingsError}
import tech.beshu.ror.implicits.*

import scala.language.postfixOps

class RorTestSettingsManager(esConfigBasedRorSettings: EsConfigBasedRorSettings,
                             fileSettingsLoader: FileRorSettingsLoader,
                             indexSettingsManager: IndexSettingsManager[TestRorSettings])
  extends SettingsManager[TestRorSettings] with Logging {

  def loadFromIndexWithFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                fallbackSettings: TestRorSettings): Task[Either[LoadingFromIndexError, TestRorSettings]] = {
    loadFromIndexWithFallback(
      loadFromIndexParameters = loadFromIndexParameters,
      fallback = Task.delay(Right(fallbackSettings))
    ).map(_.leftMap {
      case error: LoadingFromIndexError => error
      case error => throw new IllegalStateException(s"Unexpected $error type")
    })
  }

  override def loadFromIndexWithFallback(loadFromIndexParameters: LoadFromIndexParameters,
                                         fallback: Task[Either[LoadingError, TestRorSettings]]): Task[Either[LoadingError, TestRorSettings]] = {
    attemptLoadingConfigFromIndex(
      parameters = loadFromIndexParameters,
      fallback = fallback
    )
  }

  override def loadFromIndex(): Task[Either[LoadingFromIndexError, TestRorSettings]] = {
    loadTestRorConfigFromIndex(LoadingDelay.none)
  }

  override def saveToIndex(settings: TestRorSettings): Task[Either[SavingIndexSettingsError, Unit]] = {
    EitherT(indexSettingsManager.save(settings))
      .leftMap {
        case IndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings => SettingsManager.SavingIndexSettingsError.CannotSaveSettings
      }
      .value
  }

  private def attemptLoadingConfigFromIndex(parameters: LoadFromIndexParameters,
                                            fallback: Task[Either[LoadingError, TestRorSettings]]): Task[Either[LoadingError, TestRorSettings]] = {
    parameters.loadingAttemptsCount.value.value match {
      case 0 =>
        fallback
      case attemptsCount =>
        loadTestRorConfigFromIndex(LoadingDelay.none).flatMap {
          case Left(LoadingFromIndexError.IndexNotExist) =>
            attemptLoadingConfigFromIndex(
              parameters.copy(loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(parameters.loadingAttemptsCount.value.value - 1)),
              fallback = fallback
            )
          case Left(error@LoadingFromIndexError.IndexUnknownStructure) =>
            Task.now(Left(error))
          case Left(error@LoadingFromIndexError.IndexParsingError(_)) =>
            Task.now(Left(error))
          case Right(value) =>
            Task.now(Right(value))
        }
    }
  }

  private def loadTestRorConfigFromIndex(loadingDelay: LoadingDelay) = {
    val settingsIndex = indexSettingsManager.settingsIndex
    // todo: log is ok?
    logger.info(s"[CLUSTERWIDE SETTINGS] Loading ReadonlyREST test settings from index (${settingsIndex.index.show}) ...")
    EitherT {
      indexSettingsManager
        .load()
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

  private def convertIndexError(error: RorSettingsLoader.Error[LoadingIndexSettingsError]): LoadingFromIndexError =
    error match {
      case ParsingError(error) => LoadingFromIndexError.IndexParsingError(error.show)
      case SpecializedError(LoadingIndexSettingsError.IndexNotExist) => LoadingFromIndexError.IndexNotExist
      case SpecializedError(LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => LoadingFromIndexError.IndexUnknownStructure
    }

  private def logIndexLoadingError(error: LoadingFromIndexError): Unit = {
    error match {
      case LoadingFromIndexError.IndexParsingError(message) =>
        logger.error(s"Loading ReadonlyREST settings from index failed: ${message.show}")
      case LoadingFromIndexError.IndexUnknownStructure =>
        logger.info("Loading ReadonlyREST test settings from index failed: index content malformed")
      case LoadingFromIndexError.IndexNotExist =>
        logger.info("Loading ReadonlyREST test settings from index failed: cannot find index")
    }
  }

}
