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
package tech.beshu.ror.settings.strategy

import cats.data.EitherT
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.boot.ReadonlyRest.StartingFailure
import tech.beshu.ror.configuration.{RawRorSettings, TestRorSettings}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.settings.source.*
import tech.beshu.ror.settings.source.FileSettingsSource.FileSettingsLoadingError
import tech.beshu.ror.settings.source.IndexSettingsSource.IndexSettingsLoadingError
import tech.beshu.ror.settings.source.ReadOnlySettingsSource.LoadingSettingsError
import tech.beshu.ror.utils.ScalaOps.*

class RetryableIndexSourceWithFileSourceFallbackRorSettingsLoader(mainSettingsIndexSource: MainSettingsIndexSource,
                                                                  mainSettingsFileSource: MainSettingsFileSource,
                                                                  testSettingsIndexSource: TestSettingsIndexSource,
                                                                  /* todo: retry strategy*/)
  extends StartingRorSettingsLoader with Logging {

  override def load(): Task[Either[StartingFailure, (RawRorSettings, Option[TestRorSettings])]] = {
    loadMainSettingsFromIndex().orElse(loadMainSettingsFromFile()).value
    val result = for {
      mainSettings <- loadMainSettingsFromIndex().orElse(loadMainSettingsFromFile())
      testSettings <- loadTestSettingsFromIndex().recover { case failure => Option.empty[TestRorSettings] }
    } yield (mainSettings, testSettings)
    result.value
  }

  // todo: don't forget about porting this part of code:
  //          _ <- wait(parameters.loadingDelay.value.value)
  private def loadMainSettingsFromIndex() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST main settings from index (${mainSettingsIndexSource.settingsIndex.show}) ..."))
      loadedSettings <- EitherT(mainSettingsIndexSource.load())
        .biSemiflatTap(
          error => logger.dInfo(s"Loading ReadonlyREST main settings from index failed: "), // todo: ${error.show}")
            // todo:
            //              case LoadingFromIndexError.IndexParsingError(message) =>
            //                logger.dError(s"Loading ReadonlyREST settings from index failed: ${message.show}")
            //              case LoadingFromIndexError.IndexUnknownStructure =>
            //                logger.dInfo(s"Loading ReadonlyREST settings from index failed: index content malformed")
            //              case LoadingFromIndexError.IndexNotExist =>
            //                logger.dInfo(s"Loading ReadonlyREST settings from index failed: cannot find index")
          settings => logger.dDebug(s"Loaded ReadonlyREST main settings from index: ${settings.raw.show}")
        )
        .leftMap(convertIndexError)
    } yield loadedSettings
  }

  private def convertIndexError(error: IndexSettingsLoadingError): StartingFailure = error match {
    case LoadingSettingsError.SettingsMalformed(_) => ???
    case LoadingSettingsError.SourceSpecificError(IndexSettingsSource.LoadingError.IndexNotFound) => ???
    case LoadingSettingsError.SourceSpecificError(IndexSettingsSource.LoadingError.DocumentNotFound) => ???
  }

  private def loadMainSettingsFromFile() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST settings from file: ${mainSettingsFileSource.settingsFile.show}"))
      loadedSettings <- EitherT(mainSettingsFileSource.load())
        .biSemiflatTap(
          error => logger.dError(s"Loading ReadonlyREST settings from file failed:"), // todo: ${error.show}"),
          settings => logger.dDebug(s"Loaded ReadonlyREST settings from index: ${settings.raw.show}")
        )
        .leftMap(convertFileError)
    } yield loadedSettings
  }

  private def convertFileError(error: FileSettingsLoadingError): StartingFailure = error match {
    case LoadingSettingsError.SettingsMalformed(_) => ???
    case LoadingSettingsError.SourceSpecificError(FileSettingsSource.LoadingError.FileNotExist(_)) => ???
  }

  private def loadTestSettingsFromIndex() = {
    for {
      _ <- lift(logger.info(s"Loading ReadonlyREST test settings from index (${testSettingsIndexSource.settingsIndex.show}) ..."))
      loadedSettings <- EitherT(testSettingsIndexSource.load())
        .biSemiflatTap(
          error => logger.dInfo(s"Loading ReadonlyREST test settings from index failed: "), // todo: ${error.show}")
          // todo:
          //              case LoadingFromIndexError.IndexParsingError(message) =>
          //                logger.dError(s"Loading ReadonlyREST settings from index failed: ${message.show}")
          //              case LoadingFromIndexError.IndexUnknownStructure =>
          //                logger.dInfo(s"Loading ReadonlyREST settings from index failed: index content malformed")
          //              case LoadingFromIndexError.IndexNotExist =>
          //                logger.dInfo(s"Loading ReadonlyREST settings from index failed: cannot find index")
          settings => logger.dDebug(s"Loaded ReadonlyREST test settings from index: ${settings.rawSettings.raw.show}")
        )
        .leftMap(convertIndexError)
        .map(Option(_))
    } yield loadedSettings
  }

  private def lift[A](value: => A): EitherT[Task, Nothing, A] = EitherT(Task.delay(Right(value)))

  //  private def toStartingFailure(error: LoadingError) = {
  //    error match {
  //      case Left(LoadingSettingsError.FormatError) =>
  //        StartingFailure(???)
  //      case Right(LoadingSettingsError.FormatError) =>
  //        StartingFailure(???)
  //      //      case Left(LoadingSettingsError.FileParsingError(message)) =>
  //      //        StartingFailure(message)
  //      //      case Left(LoadingFromFileError.FileNotExist(file)) =>
  //      //        StartingFailure(s"Cannot find settings file: ${file.show}")
  //      //      case Right(LoadingFromIndexError.IndexParsingError(message)) =>
  //      //        StartingFailure(message)
  //      //      case Right(LoadingFromIndexError.IndexUnknownStructure) =>
  //      //        StartingFailure(s"Settings index is malformed")
  //      //      case Right(LoadingFromIndexError.IndexNotExist) =>
  //      //        StartingFailure(s"Settings index doesn't exist")
  //    }
  //  }
  //
  //  private def loadTestSettings(esConfigBasedRorSettings: EsConfigBasedRorSettings,
  //                               indexSettingsSource: IndexSettingsSource[TestRorSettings]): EitherT[Task, StartingFailure, TestRorSettings] = {
  //    esConfig.loadingRorCoreStrategy match {
  //      case LoadingRorCoreStrategy.ForceLoadingFromFile(_) =>
  //        EitherT.rightT[Task, StartingFailure](TestRorSettings.NotSet)
  //      case LoadingRorCoreStrategy.LoadFromIndexWithFileFallback(parameters, _) =>
  //        EitherT(new TestSettingsIndexOnlyLoadingStrategy(indexSettingsSource).load())
  //          .leftFlatMap {
  //            case LoadingSettingsError.FormatError => ???
  //            // todo:
  //            //          case LoadingFromIndexError.IndexParsingError(message) =>
  //            //            logger.error(s"Loading ReadonlyREST test settings from index failed: ${message.show}. No test settings will be loaded.")
  //            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
  //            //          case LoadingFromIndexError.IndexUnknownStructure =>
  //            //            logger.error("Loading ReadonlyREST test settings from index failed: index content malformed. No test settings will be loaded.")
  //            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
  //            //          case LoadingFromIndexError.IndexNotExist =>
  //            //            logger.info("Loading ReadonlyREST test settings from index failed: cannot find index. No test settings will be loaded.")
  //            //            EitherT.rightT[Task, StartingFailure](notSetTestRorSettings)
  //          }
  //    }
  //  }

}
