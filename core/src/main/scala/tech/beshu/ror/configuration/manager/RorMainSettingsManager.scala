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
import cats.implicits.toShow
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.configuration.EsConfigBasedRorSettings.LoadingRorCoreStrategy
import tech.beshu.ror.configuration.RorProperties.{LoadingAttemptsCount, LoadingAttemptsInterval}
import tech.beshu.ror.configuration.index.{IndexJsonContentServiceBasedIndexMainSettingsManager, IndexSettingsManager}
import tech.beshu.ror.configuration.loader.RorSettingsLoader.Error.{ParsingError, SpecializedError}
import tech.beshu.ror.configuration.loader.{FileRorSettingsLoader, RorSettingsLoader}
import tech.beshu.ror.configuration.manager.FileSettingsManager.LoadingFromFileError
import tech.beshu.ror.configuration.manager.InIndexSettingsManager.{LoadingFromIndexError, SavingIndexSettingsError}
import tech.beshu.ror.configuration.manager.RorMainSettingsManager.LoadingError
import tech.beshu.ror.configuration.{EsConfigBasedRorSettings, RawRorSettings, RawRorSettingsYamlParser}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.ScalaOps.LoggerOps

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

class RorMainSettingsManager private(esConfigBasedRorSettings: EsConfigBasedRorSettings,
                                     fileSettingsLoader: FileRorSettingsLoader,
                                     indexSettingsManager: IndexSettingsManager[RawRorSettings])
  extends FileSettingsManager[RawRorSettings]
    with InIndexSettingsManager[RawRorSettings]
    with Logging {

  def loadAccordingToStrategy(): Task[Either[LoadingError, RawRorSettings]] = {
    def loadFromFileWithLoadingError(): Task[Either[LoadingError, RawRorSettings]] =
      loadFromFile().map(_.left.map(Left.apply))

    esConfigBasedRorSettings.loadingRorCoreStrategy match {
      case LoadingRorCoreStrategy.ForceLoadingFromFile(parameters) =>
        loadFromFileWithLoadingError()
      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, fallbackParameters) =>
        for {
          _ <- wait(parameters.loadingDelay.value.value)
          result <- attemptLoadingFromIndex(
            loadingAttemptsInterval = parameters.loadingAttemptsInterval,
            loadingAttemptsCount = parameters.loadingAttemptsCount,
            fallback = loadFromFileWithLoadingError()
          )
        } yield result
    }
  }

  override def loadFromFile(): Task[Either[LoadingFromFileError, RawRorSettings]] = {
    val result = for {
      _ <- lift(logger.info(s"Loading ReadonlyREST settings from file: ${fileSettingsLoader.settingsFile.show}"))
      settings <- EitherT(fileSettingsLoader.load())
        .leftMap(convertFileError)
        .leftSemiflatTap { error =>
          logger.dError(s"Loading ReadonlyREST settings from file failed: ${error.toString}")
        }
    } yield settings
    result.value
  }

  override def loadFromIndex(): Task[Either[LoadingFromIndexError, RawRorSettings]] = {
    loadRorSettingsFromIndex()
  }

  override def saveToIndex(settings: RawRorSettings): Task[Either[SavingIndexSettingsError, Unit]] = {
    EitherT(indexSettingsManager.save(settings))
      .leftMap {
        case IndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings => InIndexSettingsManager.SavingIndexSettingsError.CannotSaveSettings
      }
      .value
  }

  private def attemptLoadingFromIndex(loadingAttemptsInterval: LoadingAttemptsInterval,
                                      loadingAttemptsCount: LoadingAttemptsCount,
                                      fallback: Task[Either[LoadingError, RawRorSettings]]): Task[Either[LoadingError, RawRorSettings]] = {
    loadingAttemptsCount.value.value match {
      case 0 =>
        fallback.map(identity)
      case attemptsCount =>
        loadRorSettingsFromIndex()
          .flatMap {
            case Left(LoadingFromIndexError.IndexNotExist) =>
              for {
                _ <- wait(loadingAttemptsInterval.value.value)
                result <- attemptLoadingFromIndex(
                  loadingAttemptsInterval = loadingAttemptsInterval,
                  loadingAttemptsCount = LoadingAttemptsCount.unsafeFrom(loadingAttemptsCount.value.value - 1),
                  fallback = fallback
                )
              } yield result
            case Left(LoadingFromIndexError.IndexUnknownStructure) =>
              Task.now(Left(Right(LoadingFromIndexError.IndexUnknownStructure)))
            case Left(error@LoadingFromIndexError.IndexParsingError(_)) =>
              Task.now(Left(Right(error)))
            case Right(value) =>
              Task.now(Right(value))
          }
    }
  }

  private def loadRorSettingsFromIndex() = {
    val settingsIndex = indexSettingsManager.settingsIndex
    val result = for {
      _ <- lift(logger.info(s"Loading ReadonlyREST settings from index (${settingsIndex.index.show}) ..."))
      settings <- EitherT(indexSettingsManager.load())
        .leftMap(convertIndexError)
        .biSemiflatTap(
          {
            case LoadingFromIndexError.IndexParsingError(message) =>
              logger.dError(s"Loading ReadonlyREST settings from index failed: ${message.show}")
            case LoadingFromIndexError.IndexUnknownStructure =>
              logger.dInfo(s"Loading ReadonlyREST settings from index failed: index content malformed")
            case LoadingFromIndexError.IndexNotExist =>
              logger.dInfo(s"Loading ReadonlyREST settings from index failed: cannot find index")
          },
          rorSettings => {
            logger.dDebug(s"Loaded ReadonlyREST settings from index: ${rorSettings.raw.show}")
          }
        )
    } yield settings
    result.value
  }

  private def convertFileError(error: RorSettingsLoader.Error[FileRorSettingsLoader.Error]): LoadingFromFileError = {
    error match {
      case ParsingError(error) => LoadingFromFileError.FileParsingError(error.show)
      case SpecializedError(FileRorSettingsLoader.Error.FileNotExist(file)) => LoadingFromFileError.FileNotExist(file.path)
    }
  }

  private def convertIndexError(error: RorSettingsLoader.Error[IndexSettingsManager.LoadingIndexSettingsError]): LoadingFromIndexError =
    error match {
      case ParsingError(error) => LoadingFromIndexError.IndexParsingError(error.show)
      case SpecializedError(IndexSettingsManager.LoadingIndexSettingsError.IndexNotExist) => LoadingFromIndexError.IndexNotExist
      case SpecializedError(IndexSettingsManager.LoadingIndexSettingsError.UnknownStructureOfIndexDocument) => LoadingFromIndexError.IndexUnknownStructure
    }

  private def wait(duration: FiniteDuration) = {
    Task.sleep(duration).map(Right.apply)
  }

  private def lift[A](value: => A): EitherT[Task, Nothing, A] = EitherT(Task.delay(Right(value)))
}

object RorMainSettingsManager {

  type LoadingError = Either[LoadingFromFileError, LoadingFromIndexError]

  def create(esConfigBasedRorSettings: EsConfigBasedRorSettings,
             indexJsonContentService: IndexJsonContentService): RorMainSettingsManager = {
    val rorSettingsFile = esConfigBasedRorSettings.loadingRorCoreStrategy.rorSettingsFile
    val yamlParser = RawRorSettingsYamlParser(esConfigBasedRorSettings.loadingRorCoreStrategy.rorSettingsMaxSize)
    new RorMainSettingsManager(
      esConfigBasedRorSettings,
      new FileRorSettingsLoader(rorSettingsFile, yamlParser),
      new IndexJsonContentServiceBasedIndexMainSettingsManager(
        esConfigBasedRorSettings.rorSettingsIndex,
        yamlParser,
        indexJsonContentService,
      )
    )
  }

}